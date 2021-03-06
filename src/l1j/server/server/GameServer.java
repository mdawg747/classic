/*
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2, or (at your option)
 * any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA
 * 02111-1307, USA.
 *
 * http://www.gnu.org/copyleft/gpl.html
 */
package l1j.server.server;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import l1j.server.server.datatables.SkillTable;
import l1j.server.server.model.L1World;
import l1j.server.server.model.Instance.L1PcInstance;
import l1j.server.server.network.Client;
import l1j.server.server.network.NetworkServer;

public class GameServer extends Thread {
	//private ServerSocket _serverSocket;
	private static Logger _log = LoggerFactory.getLogger(GameServer.class.getName());
	//private int _port;

	// Naive denial of service defense.
	//private static final int CONNECTION_LIMIT = 20;
	private static final int CACHE_REFRESH = 1000 * 60 * 4;
	// Might be overkill, but hard to test. =\
	private static final ConcurrentMap<String, Integer> connectionCache = new ConcurrentHashMap<String, Integer>();

	static {
		GeneralThreadPool.getInstance().scheduleAtFixedRate(new Runnable() {
			@Override
			public void run() {
				try {
					connectionCache.clear();
				} catch (Exception e) {
					// TODO Auto-generated catch block
					_log.error("",e);
				}
			}
		}, CACHE_REFRESH, CACHE_REFRESH);
	}

//	@Override
//	public void run() {
//		_log.info("Server started. Memory used: "
//				+ SystemUtil.getUsedMemoryMB() + "MB");
//		_log.info("Waiting for connections!");
//		while (true) {
//			try {
//				Socket socket = _serverSocket.accept();
//				String host = socket.getInetAddress().getHostAddress();
//
//				connectionCache.putIfAbsent(host, 1);
//				
//				try {
//					if (connectionCache.get(host) == CONNECTION_LIMIT) {
//						// Log DOS detection once, but not more than once
//						_log.warn(
//						"GameServer::run: " + host + " hit connection limit.");
//					} else if (connectionCache.get(host) > CONNECTION_LIMIT) {
//						socket.close();
//					} else if (IpTable.getInstance().isBannedIp(host)) {
//						_log.info("Banned IP(" + host + ")");
//					} else {
//						_log.trace("Accepted connection from IP: "
//								+ socket.getInetAddress());
//						Client client = new Client(socket);
//						GeneralThreadPool.getInstance().execute(client);
//					}
//				
//					connectionCache.replace(host, connectionCache.get(host) + 1);
//				} catch(NullPointerException ex) { 
//					// Typically happens if the cache was cleared between the putIfAbsent and the get call
//					// So just close the socket and let them connect again
//					socket.close();
//				}
//			} catch (IOException ioexception) {
//				_log.error("Error creating a client thread connection! IO Exception!");
//				_log.error(ioexception.getLocalizedMessage(), ioexception);
//			} catch(Exception ex) {
//				_log.error("Error creating a client thread connection! General Exception!");
//				_log.error(ex.getLocalizedMessage(), ex);
//				throw ex;
//			}
//		}
//	}

	private static GameServer _instance;

	private GameServer() {
		super("GameServer");
	}

	public static GameServer getInstance() {
		if (_instance == null) {
			_instance = new GameServer();
		}
		return _instance;
	}

	public void initialize() throws Exception {
//		String s = Config.GAME_SERVER_HOST_NAME;

		_log.info("=================================================");
		_log.info("               L1J-En Server Starting");
		_log.info("=================================================");

		//_port = Config.GAME_SERVER_PORT;
//		if (!"*".equals(s)) {
//			InetAddress inetaddress = InetAddress.getByName(s);
//			inetaddress.getHostAddress();
//			_serverSocket = new ServerSocket(_port, 50, inetaddress);
//			_log.info("Login Server ready on "
//					+ (inetaddress == null ? "Port" : inetaddress
//							.getHostAddress()) + ":" + _port);
//		} else {
//			_serverSocket = new ServerSocket(_port);
//			_log.info("Port " + _port + " opened");
//		}
		_log.info("Thread Lock Detection running");
		SkillTable.initialize();
		GameServerThread.getInstance();
		Runtime.getRuntime().addShutdownHook(Shutdown.getInstance());
	    Thread thread = new Thread(NetworkServer.getInstance());
	    thread.start();
		//this.start();
	}

	/**
	 * All players online to kick, character and preservation of information.
	 */
	public void disconnectAllCharacters() {
		Collection<L1PcInstance> players = L1World.getInstance()
				.getAllPlayers();
		for (L1PcInstance pc : players) {
			pc.getNetConnection().setActiveChar(null);
			pc.getNetConnection().kick();
		}
		// Kick save after all, make
		for (L1PcInstance pc : players) {
			Client.quitGame(pc, "--SENT FROM DISCONNECTALL--");
			L1World.getInstance().removeObject(pc);
		}
	}

	private class ServerShutdownThread extends Thread {
		private final int _secondsCount;

		public ServerShutdownThread(int secondsCount) {
			_secondsCount = secondsCount;
		}

		@Override
		public void run() {
			L1World world = L1World.getInstance();
			try {
				int secondsCount = _secondsCount;
				world.broadcastServerMessage("Get to a safe spot. The server will restart soon!");
				world.broadcastServerMessage("Repeat, the server is going down for maintenance soon!");
				while (0 < secondsCount) {
					if (secondsCount <= 10) {
						world.broadcastServerMessage("Server will shutdown in "
								+ secondsCount
								+ " seconds.  Please get to a safe area and logout.");
					} else {
						if (secondsCount % 60 == 0) {
							world.broadcastServerMessage("Server will shutdown in "
									+ secondsCount / 60 + " minutes.");
						}
					}
					Thread.sleep(1000);
					secondsCount--;
				}
				shutdown();
			} catch (InterruptedException e) {
				world.broadcastServerMessage("Server shutdown aborted!  You may continue playing!");
				return;
			}
		}
	}

	private ServerShutdownThread _shutdownThread = null;
	
	public synchronized boolean isShuttingDown() {
		return _shutdownThread != null;
	}

	public synchronized void shutdownWithCountdown(int secondsCount) {
		if (_shutdownThread != null) {
			// Shutting down the request is already being done
			// TODO error may need to be notified
			return;
		}
		_shutdownThread = new ServerShutdownThread(secondsCount);
		GeneralThreadPool.getInstance().execute(_shutdownThread);
	}

	public void shutdown() {
		disconnectAllCharacters();
		System.exit(0);
	}

	public synchronized void abortShutdown() {
		if (_shutdownThread == null) {
			// Shutting down the request has not been
			// TODO error may need to be notified
			return;
		}
		_shutdownThread.interrupt();
		_shutdownThread = null;
	}
}
