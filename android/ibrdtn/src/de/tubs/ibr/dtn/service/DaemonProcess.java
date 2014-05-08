/*
 * DaemonMainThread.java
 * 
 * Copyright (C) 2013 IBR, TU Braunschweig
 *
 * Written-by: Dominik Schürmann <dominik@dominikschuermann.de>
 * 	           Johannes Morgenroth <morgenroth@ibr.cs.tu-bs.de>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package de.tubs.ibr.dtn.service;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import de.tubs.ibr.dtn.DaemonState;
import de.tubs.ibr.dtn.api.Node;
import de.tubs.ibr.dtn.api.SingletonEndpoint;
import de.tubs.ibr.dtn.daemon.Preferences;
import de.tubs.ibr.dtn.keyexchange.KeyExchangeService;
import de.tubs.ibr.dtn.swig.DaemonRunLevel;
import de.tubs.ibr.dtn.swig.NativeDaemon;
import de.tubs.ibr.dtn.swig.NativeDaemonCallback;
import de.tubs.ibr.dtn.swig.NativeDaemonException;
import de.tubs.ibr.dtn.swig.NativeEventCallback;
import de.tubs.ibr.dtn.swig.NativeKeyInfo;
import de.tubs.ibr.dtn.swig.NativeNode;
import de.tubs.ibr.dtn.swig.NativeStats;
import de.tubs.ibr.dtn.swig.StringVec;

public class DaemonProcess {
	private final static String TAG = "DaemonProcess";

	private NativeDaemon mDaemon = null;
	private DaemonProcessHandler mHandler = null;
	private Context mContext = null;
	private DaemonState mState = DaemonState.OFFLINE;
	private Boolean mDiscoveryEnabled = null;
	private Boolean mDiscoveryActive = null;
	
    private WifiManager.MulticastLock mMcastLock = null;

	private final static String GNUSTL_NAME = "gnustl_shared";
	private final static String CRYPTO_NAME = "cryptox";
	private final static String SSL_NAME = "ssl";
	private final static String IBRCOMMON_NAME = "ibrcommon";
	private final static String IBRDTN_NAME = "ibrdtn";
	private final static String DTND_NAME = "dtnd";
	private final static String ANDROID_GLUE_NAME = "android-glue";

    // CloudUplink Parameter
    private static final SingletonEndpoint __CLOUD_EID__ = new SingletonEndpoint(
            "dtn://cloud.dtnbone.dtn");
    private static final String __CLOUD_PROTOCOL__ = "tcp";
    private static final String __CLOUD_ADDRESS__ = "134.169.35.130"; // quorra.ibr.cs.tu-bs.de";
    private static final String __CLOUD_PORT__ = "4559";
    
    public interface OnRestartListener {
        public void OnStop(DaemonRunLevel previous, DaemonRunLevel next);
        public void OnReloadConfiguration();
        public void OnStart(DaemonRunLevel previous, DaemonRunLevel next);
    };

	/**
	 * Loads all shared libraries in the right order with System.loadLibrary()
	 */
	private static void loadLibraries()
	{
		try
		{
			System.loadLibrary(GNUSTL_NAME);

			System.loadLibrary(CRYPTO_NAME);
			System.loadLibrary(SSL_NAME);

			System.loadLibrary(IBRCOMMON_NAME);
			System.loadLibrary(IBRDTN_NAME);
			System.loadLibrary(DTND_NAME);

			System.loadLibrary(ANDROID_GLUE_NAME);
		} catch (UnsatisfiedLinkError e)
		{
			Log.e(TAG, "UnsatisfiedLinkError! Are you running special hardware?", e);
		} catch (Exception e)
		{
			Log.e(TAG, "Loading the libraries failed!", e);
		}
	}
	
	static
	{
		// load libraries on first use of this class
		loadLibraries();
	}

	public DaemonProcess(Context context, DaemonProcessHandler handler) {
		this.mDaemon = new NativeDaemon(mDaemonCallback, mEventCallback);
		this.mContext = context;
		this.mHandler = handler;
		this.mDiscoveryEnabled = false;
		this.mDiscoveryActive = true;
	}

	public String[] getVersion() {
        StringVec version = mDaemon.getVersion();
        return new String[] { version.get(0), version.get(1) };
	}
	
	public synchronized NativeStats getStats() {
	    return mDaemon.getStats();
	}
	
	public synchronized List<Node> getNeighbors() {
        List<Node> ret = new LinkedList<Node>();
        StringVec neighbors = mDaemon.getNeighbors();
        for (int i = 0; i < neighbors.size(); i++) {
        	String eid = neighbors.get(i);
        	
        	try {
            	// get extended info
				NativeNode nn = mDaemon.getInfo(eid);
				
            	Node n = new Node();
            	n.endpoint = new SingletonEndpoint(eid);
            	n.type = nn.getType().toString();
                ret.add(n);
			} catch (NativeDaemonException e) { }
        }

        return ret;
	}
	
	public synchronized void clearStorage() {
		mDaemon.clearStorage();
	}
	
	public DaemonState getState() {
	    return mState;
	}
	
    private void setState(DaemonState newState) {
        if (mState.equals(newState)) return;
        mState = newState;
        mHandler.onStateChanged(mState);
    }
    
    public synchronized void initiateConnection(String endpoint) {
    	if (getState().equals(DaemonState.ONLINE)) {
    		mDaemon.initiateConnection(endpoint);
    	}
    }
    
    public synchronized void startKeyExchange(String eid, int protocol, String password) {
    	mDaemon.onKeyExchangeBegin(eid, protocol, password);
    }
    
    public synchronized void givePasswordResponse(String eid, int session, String password) {
    	mDaemon.onKeyExchangeResponse(eid, 2, session, 0, "");
    }
    
    public synchronized void giveHashResponse(String eid, int session, int equals) {
    	mDaemon.onKeyExchangeResponse(eid, 100, session, equals, "");
    }
    
    public synchronized void giveNewKeyResponse(String eid, int session, int newKey) {
    	mDaemon.onKeyExchangeResponse(eid, 101, session, newKey, "");
    }
    
    public synchronized void giveQRResponse(String eid, String data) {
    	mDaemon.onKeyExchangeBegin(eid, 4, data);
    }
    
    public synchronized void giveNFCResponse(String eid, String data) {
    	mDaemon.onKeyExchangeBegin(eid, 5, data);
    }
    
    public synchronized void removeKey(SingletonEndpoint endpoint) {
    	try {
			mDaemon.removeKey(endpoint.toString());
		} catch (NativeDaemonException e) {
			Log.e(TAG, "", e);
		}
    }
    
    public synchronized Bundle getKeyInfo(SingletonEndpoint endpoint) {
		try {
			NativeKeyInfo info = mDaemon.getKeyInfo(endpoint.toString());
			Bundle ret = new Bundle();
			ret.putString("fingerprint", info.getFingerprint());
			ret.putString(KeyExchangeService.EXTRA_DATA, info.getData());
			ret.putLong(KeyExchangeService.EXTRA_FLAGS, info.getFlags());
			
    		long flags = info.getFlags();
    		int trustlevel = 0;
    		
    		boolean pNone = (flags & 0x01) > 0;
    		boolean pDh = (flags & 0x02) > 0;
    		boolean pJpake = (flags & 0x04) > 0;
    		boolean pHash = (flags & 0x08) > 0;
    		boolean pQrCode = (flags & 0x10) > 0;
    		boolean pNfc = (flags & 0x20) > 0;
    		
    		if (pNfc || pQrCode) {
    			trustlevel = 100;
    		}
    		else if (pHash || pJpake) {
    			trustlevel = 60;
    		}
    		else if (pDh) {
    			trustlevel = 10;
    		}
    		else if (pNone) {
    			trustlevel = 1;
    		}
			
			ret.putInt("trustlevel", trustlevel);
			return ret;
		} catch (NativeDaemonException e) {
			return null;
		}
    }
    
    public synchronized void initialize() {
    	// lower the thread priority
    	android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
    	
    	// get daemon preferences
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this.mContext);
        
        // enable debug based on prefs
        int logLevel = 0;
        try {
            logLevel = Integer.valueOf(preferences.getString("log_options", "0"));
        } catch (java.lang.NumberFormatException e) {
            // invalid number
        }
        
        int debugVerbosity = 0;
        try {
            debugVerbosity = Integer.valueOf(preferences.getString("log_debug_verbosity", "0"));
        } catch (java.lang.NumberFormatException e) {
            // invalid number
        }
        
        // disable debugging if the log level is lower than 3
        if (logLevel < 3) debugVerbosity = 0;
        
        // set logging options
        mDaemon.setLogging("Core", logLevel);

        // set logfile options
        String logFilePath = null;
        
        if (preferences.getBoolean("log_enable_file", false)) {
            File logPath = DaemonStorageUtils.getStoragePath("logs");
            if (logPath != null) {
                logPath.mkdirs();
                Calendar cal = Calendar.getInstance();
                String time = "" + cal.get(Calendar.YEAR) + cal.get(Calendar.MONTH) + cal.get(Calendar.DAY_OF_MONTH) + cal.get(Calendar.DAY_OF_MONTH)
                        + cal.get(Calendar.HOUR) + cal.get(Calendar.MINUTE) + cal.get(Calendar.SECOND);
                
                logFilePath = logPath.getPath() + File.separatorChar + "ibrdtn_" + time + ".log";
            }
        }

        if (logFilePath != null) {
            // enable file logging
            mDaemon.setLogFile(logFilePath, logLevel);
        } else {
            // disable file logging
            mDaemon.setLogFile("", 0);
        }

        // set debug verbosity
        mDaemon.setDebug(debugVerbosity);
        
        // initialize daemon configuration
        onConfigurationChanged();
        
        try {
            mDaemon.init(DaemonRunLevel.RUNLEVEL_API);
        } catch (NativeDaemonException e) {
            Log.e(TAG, "error while initializing the daemon process", e);
        }
        
        // listen to preference changes
        preferences.registerOnSharedPreferenceChangeListener(mPrefListener);
    }
	
	public synchronized void start() {
        // reload daemon configuration
        onConfigurationChanged();
        
	    try {
            mDaemon.init(DaemonRunLevel.RUNLEVEL_ROUTING_EXTENSIONS);
        } catch (NativeDaemonException e) {
            Log.e(TAG, "error while starting the daemon process", e);
        }
	}
	
	public synchronized void stop() {
	    // stop the running daemon
	    try {
            mDaemon.init(DaemonRunLevel.RUNLEVEL_API);
        } catch (NativeDaemonException e) {
            Log.e(TAG, "error while stopping the daemon process", e);
        }
	}
	
	public synchronized void restart(Integer runlevel, OnRestartListener listener) {
	    // restart the daemon
        DaemonRunLevel restore = mDaemon.getRunLevel();
        DaemonRunLevel rl = DaemonRunLevel.swigToEnum(runlevel);
        
        // do not restart if the current runlevel is below or equal
        if (restore.swigValue() <= runlevel) {
            // reload configuration
            onConfigurationChanged();
            if (listener != null) listener.OnReloadConfiguration();
            return;
        }
        
	    try {
	        // bring the daemon down
	        if (listener != null) listener.OnStop(restore, rl);
	        mDaemon.init(rl);
	        
	        // reload configuration
	        onConfigurationChanged();
	        if (listener != null) listener.OnReloadConfiguration();
	        
	        // restore the old runlevel
	        mDaemon.init(restore);
	        if (listener != null) listener.OnStart(rl, restore);
	    } catch (NativeDaemonException e) {
            Log.e(TAG, "error while restarting the daemon process", e);
        }
	}
	
    public synchronized void destroy() {
        // get daemon preferences
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this.mContext);
        
        // unlisten to preference changes
        preferences.unregisterOnSharedPreferenceChangeListener(mPrefListener);
        
        // stop the running daemon
        try {
            mDaemon.init(DaemonRunLevel.RUNLEVEL_ZERO);
        } catch (NativeDaemonException e) {
            Log.e(TAG, "error while destroying the daemon process", e);
        }
    }
    
    private final static HashMap<String, DaemonRunLevel> mRestartMap = initializeRestartMap();
    
    private final static HashMap<String, DaemonRunLevel> initializeRestartMap() {
        HashMap<String, DaemonRunLevel> ret = new HashMap<String, DaemonRunLevel>();
        
        ret.put(Preferences.KEY_ENDPOINT_ID, DaemonRunLevel.RUNLEVEL_CORE);
        ret.put("routing", DaemonRunLevel.RUNLEVEL_ROUTING_EXTENSIONS);
        ret.put("interface_", DaemonRunLevel.RUNLEVEL_NETWORK);
        ret.put("timesync_mode", DaemonRunLevel.RUNLEVEL_API);
        ret.put("storage_mode", DaemonRunLevel.RUNLEVEL_CORE);
        ret.put("uplink_mode", DaemonRunLevel.RUNLEVEL_NETWORK);
        
        return ret;
    }
    
    private final static HashSet<String> mConfigurationSet = initializeConfigurationSet();
    
    private final static HashSet<String> initializeConfigurationSet() {
        HashSet<String> ret = new HashSet<String>();
              
        ret.add("security_mode");
        ret.add("security_bab_key");
        ret.add("log_options");
        ret.add("log_debug_verbosity");
        ret.add("log_enable_file");
        
        return ret;
    }
    
    private SharedPreferences.OnSharedPreferenceChangeListener mPrefListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
        @Override
        public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
            if (mRestartMap.containsKey(key)) {
                Log.d(TAG, "Preference " + key + " has changed => restart");
                
                // check runlevel and restart some runlevels if necessary
                final Intent intent = new Intent(DaemonProcess.this.mContext, DaemonService.class);
                intent.setAction(de.tubs.ibr.dtn.service.DaemonService.ACTION_RESTART);
                intent.putExtra("runlevel", mRestartMap.get(key).swigValue() - 1);
                DaemonProcess.this.mContext.startService(intent);
            }
            else if (key.equals(Preferences.KEY_ENABLED))
            {
                Log.d(TAG, "Preference " + key + " has changed to " + String.valueOf( prefs.getBoolean(key, false) ));
                
                if (prefs.getBoolean(key, false)) {
                    // startup the daemon process
                    final Intent intent = new Intent(DaemonProcess.this.mContext, DaemonService.class);
                    intent.setAction(de.tubs.ibr.dtn.service.DaemonService.ACTION_STARTUP);
                    DaemonProcess.this.mContext.startService(intent);
                } else {
                    // shutdown the daemon
                    final Intent intent = new Intent(DaemonProcess.this.mContext, DaemonService.class);
                    intent.setAction(de.tubs.ibr.dtn.service.DaemonService.ACTION_SHUTDOWN);
                    DaemonProcess.this.mContext.startService(intent);
                }
            }
            else if (key.startsWith("interface_"))
            {
                Log.d(TAG, "Preference " + key + " has changed => restart");
                
                // a interface has been removed or added
                // check runlevel and restart some runlevels if necessary
                final Intent intent = new Intent(DaemonProcess.this.mContext, DaemonService.class);
                intent.setAction(de.tubs.ibr.dtn.service.DaemonService.ACTION_RESTART);
                intent.putExtra("runlevel", mRestartMap.get("interface_").swigValue() - 1);
                DaemonProcess.this.mContext.startService(intent);
            }
            else if (key.startsWith("log_options"))
            {
                Log.d(TAG, "Preference " + key + " has changed to " + prefs.getString(key, "<not set>"));
                
                int logLevel = Integer.valueOf(prefs.getString("log_options", "0"));
                int debugVerbosity = Integer.valueOf(prefs.getString("log_debug_verbosity", "0"));

                // disable debugging if the log level is lower than 3
                if (logLevel < 3) debugVerbosity = 0;

                synchronized(DaemonProcess.this) {
                    // set logging options
                    mDaemon.setLogging("Core", logLevel);

                    // set debug verbosity
                    mDaemon.setDebug( debugVerbosity );
                }
            }
            else if (key.startsWith("log_debug_verbosity"))
            {
                Log.d(TAG, "Preference " + key + " has changed to " + prefs.getString(key, "<not set>"));
                
                int logLevel = Integer.valueOf(prefs.getString("log_options", "0"));
                int debugVerbosity = Integer.valueOf(prefs.getString("log_debug_verbosity", "0"));
                
                // disable debugging if the log level is lower than 3
                if (logLevel < 3) debugVerbosity = 0;
                
                synchronized(DaemonProcess.this) {
                    // set debug verbosity
                    mDaemon.setDebug( debugVerbosity );
                }
            }
            else if (key.startsWith("log_enable_file"))
            {
                Log.d(TAG, "Preference " + key + " has changed to " + prefs.getBoolean(key, false));
                
                // set logfile options
                String logFilePath = null;
                
                if (prefs.getBoolean("log_enable_file", false)) {
                    File logPath = DaemonStorageUtils.getStoragePath("logs");
                    if (logPath != null) {
                        logPath.mkdirs();
                        Calendar cal = Calendar.getInstance();
                        String time = "" + cal.get(Calendar.YEAR) + cal.get(Calendar.MONTH) + cal.get(Calendar.DAY_OF_MONTH) + cal.get(Calendar.DAY_OF_MONTH)
                                + cal.get(Calendar.HOUR) + cal.get(Calendar.MINUTE) + cal.get(Calendar.SECOND);
                        
                        logFilePath = logPath.getPath() + File.separatorChar + "ibrdtn_" + time + ".log";
                    }
                }

                synchronized(DaemonProcess.this) {
                    if (logFilePath != null) {
                        int logLevel = Integer.valueOf(prefs.getString("log_options", "0"));
                        
                        // enable file logging
                        mDaemon.setLogFile(logFilePath, logLevel);
                    } else {
                        // disable file logging
                        mDaemon.setLogFile("", 0);
                    }
                }
            } else if (mConfigurationSet.contains(key)) {
                Log.d(TAG, "Preference " + key + " has changed");
                
                // default action
                onConfigurationChanged();
            }
        }
    };
	
	private void onConfigurationChanged() {
        String configPath = mContext.getFilesDir().getPath() + "/" + "config";

        // create configuration file
        createConfig(mContext, configPath);
        
        // set configuration file
        mDaemon.setConfigFile(configPath);
	}
	
	private NativeEventCallback mEventCallback = new NativeEventCallback() {
        @Override
        public void eventRaised(String eventName, String action, StringVec data) {
            Intent event = new Intent(de.tubs.ibr.dtn.Intent.EVENT);
            Intent neighborIntent = null;
            Intent keyExchangeIntent = null;

            event.addCategory(Intent.CATEGORY_DEFAULT);
            event.putExtra("name", eventName);

            if ("NodeEvent".equals(eventName)) {
                neighborIntent = new Intent(de.tubs.ibr.dtn.Intent.NEIGHBOR);
                neighborIntent.addCategory(Intent.CATEGORY_DEFAULT);
            }
            else if ("KeyExchangeEvent".equals(eventName)) {
            	keyExchangeIntent = new Intent(KeyExchangeService.INTENT_KEY_EXCHANGE);
            	keyExchangeIntent.addCategory(Intent.CATEGORY_DEFAULT);
            }

            // place the action into the intent
            if (action.length() > 0)
            {
                event.putExtra("action", action);
                
                if (neighborIntent != null) {
                	neighborIntent.putExtra("action", action);
                }
                else if (keyExchangeIntent != null) {
                	keyExchangeIntent.putExtra("action", action);
                }
            }

            // put all attributes into the intent
            for (int i = 0; i < data.size(); i++) {
                String entry = data.get(i);
                String entry_data[] = entry.split(": ", 2);
                
                // skip invalid entries
                if (entry_data.length < 2) continue;

                event.putExtra("attr:" + entry_data[0], entry_data[1]);
                if (neighborIntent != null) {
                    neighborIntent.putExtra("attr:" + entry_data[0], entry_data[1]);
                }
                else if (keyExchangeIntent != null) {
                	keyExchangeIntent.putExtra(entry_data[0], entry_data[1]);
                }
            }

            // send event intent
            mHandler.onEvent(event);

            if (neighborIntent != null) {
                mHandler.onEvent(neighborIntent);
                mHandler.onNeighborhoodChanged();
            }
            else if (keyExchangeIntent != null) {
            	mHandler.onEvent(keyExchangeIntent);
            }

            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "EVENT intent broadcasted: " + eventName + "; Action: " + action);
            }
        }
	};
	
	private NativeDaemonCallback mDaemonCallback = new NativeDaemonCallback() {
		@Override
		public void levelChanged(DaemonRunLevel level) {
			if (DaemonRunLevel.RUNLEVEL_ROUTING_EXTENSIONS.equals(level)) {
			    setState(DaemonState.ONLINE);
			}
			else if (DaemonRunLevel.RUNLEVEL_API.equals(level)) {
			    setState(DaemonState.OFFLINE);
			}
			else if (DaemonRunLevel.RUNLEVEL_NETWORK.equals(level)) {
			    // restore previous discovery state
			    if (mDiscoveryEnabled) {
			        startDiscovery();
			    } else {
			        stopDiscovery();
			    }
			}
		}
	};

	/**
	 * Creates config for dtnd in specified path
	 * 
	 * @param context
	 */
	private void createConfig(Context context, String configPath)
	{
		// load preferences
		SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
		File config = new File(configPath);

		// remove old config file
		if (config.exists()) {
			config.delete();
		}

		try {
			FileOutputStream writer = context.openFileOutput("config", Context.MODE_PRIVATE);

			// initialize default values if configured set already
			de.tubs.ibr.dtn.daemon.Preferences.initializeDefaultPreferences(context);

			// set EID
			PrintStream p = new PrintStream(writer);
			p.println("local_uri = " + Preferences.getEndpoint(context));
			p.println("routing = " + preferences.getString("routing", "default"));
			
			// enable traffic stats
			p.println("stats_traffic = yes");

			// limit max. bundle lifetime to 30 days
			p.println("limit_lifetime = 2592000");

			// limit pre-dated timestamp to 2 weeks
			p.println("limit_predated_timestamp = 1209600");

			// limit block size to 50 MB
			p.println("limit_blocksize = 250M");
			p.println("limit_foreign_blocksize = 50M");

			// specify a security path for keys
			File sec_folder = new File(context.getFilesDir().getPath() + "/bpsec");
			if (!sec_folder.exists() || sec_folder.isDirectory()) {
				p.println("security_path = " + sec_folder.getPath());
			}
			
			// set a file for DH params
			p.println("dh_params_path = " + sec_folder.getPath() + "/dh_params.txt");
			
			String secmode = preferences.getString("security_mode", "encrypt");

			if (secmode.equals("bab")) {
				// write default BAB key to file
				String bab_key = preferences.getString("security_bab_key", "");
				File bab_file = new File(context.getFilesDir().getPath() + "/default-bab-key.mac");

				// remove old key file
				if (bab_file.exists()) bab_file.delete();

				FileOutputStream bab_output = context.openFileOutput("default-bab-key.mac", Context.MODE_PRIVATE);
				PrintStream bab_writer = new PrintStream(bab_output);
				bab_writer.print(bab_key);
				bab_writer.flush();
				bab_writer.close();

				if (bab_key.length() > 0) {
					// enable security extension: BAB
					p.println("security_level = 1");

					// add BAB key to the configuration
					p.println("security_bab_default_key = " + bab_file.getPath());
				}
			}
			
			String timesyncmode = preferences.getString("timesync_mode", "disabled");
			
			if (timesyncmode.equals("master")) {
                p.println("time_reference = yes");
                p.println("time_discovery_announcements = yes");
                p.println("time_synchronize = no");
                p.println("time_set_clock = no");
			} else if (timesyncmode.equals("slave")) {
			    p.println("time_reference = no");
                p.println("time_discovery_announcements = yes");
                p.println("time_synchronize = yes");
			    p.println("time_set_clock = no");
			    p.println("#time_sigma = 1.001");
			    p.println("#time_psi = 0.9");
			    p.println("#time_sync_level = 0.15");
			}
			
			// enable fragmentation support
			p.println("fragmentation = yes");

			// set multicast address for discovery
			p.println("discovery_address = ff02::142 224.0.0.142");

			String internet_ifaces = "";
			String ifaces = "";

			Map<String, ?> prefs = preferences.getAll();
			for (Map.Entry<String, ?> entry : prefs.entrySet()) {
				String key = entry.getKey();
				if (key.startsWith("interface_")) {
					if (entry.getValue() instanceof Boolean) {
						if ((Boolean) entry.getValue()) {
							String iface = key.substring(10, key.length());
							ifaces = ifaces + " " + iface;

							p.println("net_" + iface + "_type = tcp");
							p.println("net_" + iface + "_interface = " + iface);
							p.println("net_" + iface + "_port = 4556");
							internet_ifaces += iface + " ";
						}
					}
				}
			}

			p.println("net_interfaces = " + ifaces);

			if (!"off".equals(preferences.getString("uplink_mode", "off"))) {
				// add option to detect interface connections
				if ("wifi".equals(preferences.getString("uplink_mode", "off"))) {
					p.println("net_internet = " + internet_ifaces);

				}
				
				// add static host
				p.println("static1_address = " + __CLOUD_ADDRESS__);
				p.println("static1_port = " + __CLOUD_PORT__);
				p.println("static1_uri = " + __CLOUD_EID__);
				p.println("static1_proto = " + __CLOUD_PROTOCOL__);
				p.println("static1_immediately = yes");
				p.println("static1_global = yes");
			}

			String storage_mode = preferences.getString( "storage_mode", "disk-persistent" );
			if ("disk".equals( storage_mode ) || "disk-persistent".equals( storage_mode )) {
    			// storage path
    			File blobPath = DaemonStorageUtils.getStoragePath("blob");
    			if (blobPath != null) {
    				p.println("blob_path = " + blobPath.getPath());
    
    				// flush storage path
    				File[] files = blobPath.listFiles();
    				if (files != null) {
    					for (File f : files) {
    						f.delete();
    					}
    				}
    			}
			}

			if ("disk-persistent".equals( storage_mode )) {
    			File bundlePath = DaemonStorageUtils.getStoragePath("bundles");
    			if (bundlePath != null) {
    				p.println("storage_path = " + bundlePath.getPath());
    				p.println("use_persistent_bundlesets = yes");
    			}
			}

			// enable interface rebind
			p.println("net_rebind = yes");

			// flush the write buffer
			p.flush();

			// close the filehandle
			writer.close();
		} catch (IOException e) {
			Log.e(TAG, "Problem writing config", e);
		}
	}
	
	public synchronized void startDiscovery() {
	    if (mDiscoveryActive) return;
	    
        // set discovery flag to true
        mDiscoveryEnabled = true;
        
        WifiManager wifi_manager = (WifiManager)mContext.getSystemService(Context.WIFI_SERVICE);

        if (mMcastLock == null) {
            // listen to multicast packets
            mMcastLock = wifi_manager.createMulticastLock(TAG);
            mMcastLock.acquire();
        }
        
        // start discovery mechanism in the daemon
        mDaemon.startDiscovery();
        
        mDiscoveryActive = true;
	}
	
	public synchronized void stopDiscovery() {
	    if (!mDiscoveryActive) return;
	    
	    // set discovery flag to false
	    mDiscoveryEnabled = false;
	    
	    // stop discovery mechanism in the daemon
	    mDaemon.stopDiscovery();
	    
	    // release multicast lock
        if (mMcastLock != null) {
            mMcastLock.release();
            mMcastLock = null;
        }
        
        mDiscoveryActive = false;
	}
}
