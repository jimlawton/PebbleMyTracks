package org.meulenhoff.pebblemytracks;

import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.location.Location;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.preference.Preference.OnPreferenceChangeListener;
import android.util.Log;
import android.widget.Toast;

import com.getpebble.android.kit.Constants;
import com.getpebble.android.kit.PebbleKit;
import com.getpebble.android.kit.util.PebbleDictionary;
import com.google.android.apps.mytracks.content.MyTracksProviderUtils;
import com.google.android.apps.mytracks.content.Track;
import com.google.android.apps.mytracks.services.ITrackRecordingService;
import com.google.android.apps.mytracks.stats.TripStatistics;

import java.util.Iterator;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.meulenhoff.pebblemytracks.MyAppSettings.ParameterType;

@SuppressWarnings("unused")
public class PebbleSportsService extends Service implements OnSharedPreferenceChangeListener {
	private final String TAG = "PebbleMyTracks";

	// commands sent by smartphone
	public static final int MSG_SET_VALUES = 0x1;
	public static final int MSG_SET_NAMES = 0x2;
	public static final int MSG_SET_CURRENTSTATE = 0x3;
	public static final int MSG_SET_DESIREDSTATE = 0x4;
	
	// commands sent by the pebble
	public static final int CMD_UNKNOWN = 0x0;
	public static final int CMD_START_TRACK = 0x1;
	public static final int CMD_STOP_TRACK = 0x2;
	public static final int CMD_PAUSE_TRACK = 0x3;
	public static final int CMD_RESUME_TRACK = 0x4;
	public static final int EVENT_MYTRACKS_STARTED = 0x10;
	public static final int EVENT_MYTRACKS_STOPPED = 0x11;
	
	// mytracks states
	public static final int STATE_MYTRACKS_NOTHING = 0x1;
	public static final int STATE_MYTRACKS_RECORDING = 0x2;
	public static final int STATE_MYTRACKS_PAUSED = 0x3;
			
	public byte currentState;
	public byte desiredState;
	public byte currentCommand;
	
	private ITrackRecordingService myTracksService;
	private MyAppSettings myAppSettings;

	private int updateInterval;
	private SportsData sportsData;
	private boolean metricUnits;
	private boolean myapp;
	private UUID appUUID;
	private UUID alternativeAppUUID; // = UUID.fromString("5E1ED09C-2624-4F25-8EC1-32B0563036AC");

	private int sendConfig;

	// pebble stuff
	private PebbleKit.PebbleDataReceiver sportsDataHandler = null;
	private SharedPreferences preferences;

	// mytracks stuff
	private MyTracksProviderUtils myTracksProviderUtils;

	// timer for repetitive updates of the pebble
	private ScheduledExecutorService scheduleTaskExecutor;
	//  private Intent intent;

	
	// connection to the MyTracks service
	private ServiceConnection serviceConnection = new ServiceConnection() {
	    @Override
	    public void onServiceConnected(ComponentName className, IBinder service) {
	      myTracksService = ITrackRecordingService.Stub.asInterface(service);
	      Log.i(TAG,"Bound to MyTracks Service");
	    }

	    @Override
	    public void onServiceDisconnected(ComponentName className) {
	      
	      
	      
	      // unbind and stop the MyTracks service
	      unbindService(serviceConnection);
	      myTracksService = null;

	      Log.i(TAG,"Connection to MyTracks lost");
	      Intent intent = new Intent();
	      ComponentName componentName = new ComponentName(getString(R.string.mytracks_service_package), getString(R.string.mytracks_service_class));
	      intent.setComponent(componentName);
	      stopService(intent);	      
	    }
	  };

	
	@Override
	public void onDestroy() {
		Toast.makeText(this, "onDestroy service", Toast.LENGTH_LONG).show();

		
		stopWatchApp();

		if ( myTracksService != null ) {
			Log.i(TAG,"Stopping service");
			unbindService(serviceConnection);
		    Intent intent = new Intent();
		    ComponentName componentName = new ComponentName(getString(R.string.mytracks_service_package), getString(R.string.mytracks_service_class));
		    intent.setComponent(componentName);
		    stopService(intent);	      
		}
		
		Log.i(TAG,"Stop scheduled task");
		if ( scheduleTaskExecutor != null ) {
			scheduleTaskExecutor.shutdown();
			scheduleTaskExecutor = null;
		}

		Log.i(TAG,"Stop sportsDataHandler");

		// Always deregister any Activity-scoped BroadcastReceivers when the Activity is paused
		if (sportsDataHandler != null) {
			unregisterReceiver(sportsDataHandler);
			sportsDataHandler = null;
		}

		Log.i(TAG,"finished onDestroy");

	};

		
	@Override
	public void onCreate() {
		Log.i(TAG,"onCreate Started");
		super.onCreate();
	

		currentState = STATE_MYTRACKS_NOTHING;
		desiredState = STATE_MYTRACKS_NOTHING;
		currentCommand = CMD_UNKNOWN;
		
		
		sportsData = new SportsData();		
		myAppSettings = new MyAppSettings();
		myTracksProviderUtils = MyTracksProviderUtils.Factory.get(getApplicationContext());

		// Initialize preferences
		preferences = PreferenceManager.getDefaultSharedPreferences(this);   
		preferences.registerOnSharedPreferenceChangeListener(this);
		
		reloadPreferences();		
		
		scheduleTaskExecutor = null;
		// To receive data back from the sports watch-app, Android
		// applications must register a "DataReceiver" to operate on the
		// dictionaries received from the watch.
		//
		// In this example, we're registering a receiver to listen for
		// changes in the activity state sent from the watch, allowing
		// us the pause/resume the activity when the user presses a
		// button in the watch-app.

		startMyTracksService();
		startUpdater();
		
		Log.i(TAG,"finished onCreate");
	}
	
	private void reloadPreferences() {
		// a nasty way to check any changes in the preferences
		
		alternativeAppUUID = UUID.fromString(preferences.getString("AlternativeAppUUID", "5E1ED09C-2624-4F25-8EC1-32B0563036AC"));
		if ( preferences.getBoolean("UseAlternativeSportsApp",false) ) {
			myapp = true;
			appUUID = alternativeAppUUID;
		} else {
			myapp = false;
			appUUID = Constants.SPORTS_UUID;
		}

		updateInterval = Integer.parseInt(preferences.getString("updateInterval", "5000"));
				
		metricUnits = preferences.getBoolean("metric", true);
		for(int i=0;(i<MyAppSettings.numFields);i++) {
			String param = "parameter" + i;
			String value = preferences.getString(param,ParameterType.NOTHING.getPreferenceString());        
//			Log.i(TAG,"parameter"+ i+ ": " + value);  
			myAppSettings.setParameter(i, ParameterType.valueOf(value));
		}
		
	}
	
	@Override
	public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
		reloadPreferences();
		sendConfig = 5;
	};
	
	public void stopUpdater() {
		Log.i(TAG,"Calling stopUpdater");
		if ( scheduleTaskExecutor != null ) {
			Log.i(TAG,"Shuttinng down");
			scheduleTaskExecutor.shutdown();
			scheduleTaskExecutor = null;
		}		
		Log.i(TAG,"Done stopUpdater");

	}
	
	public void startUpdater() {
		if ( scheduleTaskExecutor != null ) {
			return;
		}
		Log.i(TAG,"Starting updater");
//		// recreate new
		
		
		
		sendConfig = 10;
		initSportsData();
		scheduleTaskExecutor= Executors.newScheduledThreadPool(5);
		scheduleTaskExecutor.scheduleWithFixedDelay(new Runnable() {
			public void run() {   
				try {
					// update sports data
					updateMyTracks();				
					updateSportsData();

					if ( myapp ) {
						updateMyApp();
					} else {
						updatePebbleSportsApp();
					}
				} catch ( Exception e ) {
					Log.i(TAG,"Caught exception: " + e.getMessage());
				}
			}    
		}, 0, updateInterval, TimeUnit.MILLISECONDS);	  

		Log.i(TAG,"Starting updater: done");	
	}
	
	public void processCommand(int cmd) {
		try {

			startUpdater();
			
			switch ( cmd ) {
			case CMD_START_TRACK:
				Log.i(TAG,"Received CMD_START_TRACK");
				desiredState = STATE_MYTRACKS_RECORDING;
				currentCommand = CMD_START_TRACK;
				break;
			case CMD_STOP_TRACK:
				Log.i(TAG,"Received CMD_STOP_TRACK");
				desiredState = STATE_MYTRACKS_NOTHING;
				currentCommand = CMD_STOP_TRACK;
				break;
			case CMD_PAUSE_TRACK:
				Log.i(TAG,"Received CMD_PAUSE_TRACK");
				desiredState = STATE_MYTRACKS_PAUSED;
				currentCommand = CMD_PAUSE_TRACK;
				break;
			case CMD_RESUME_TRACK:
				Log.i(TAG,"Received CMD_RESUME_TRACK");
				desiredState = STATE_MYTRACKS_RECORDING;
				currentCommand = CMD_RESUME_TRACK;
				break;
			case EVENT_MYTRACKS_STARTED:
				desiredState = STATE_MYTRACKS_RECORDING;
				currentCommand = CMD_UNKNOWN;
				break;
			case EVENT_MYTRACKS_STOPPED:
				desiredState = STATE_MYTRACKS_NOTHING;
				currentCommand = CMD_UNKNOWN;
				break;
			}			
		} catch ( Exception e ) {
			Log.i(TAG,"Caught exception");
		}
				
	
		if ( PebbleKit.isWatchConnected(this)) {
			PebbleDictionary mdata = new PebbleDictionary();
			mdata.addInt8(MSG_SET_CURRENTSTATE, currentState);
			mdata.addInt8(MSG_SET_DESIREDSTATE, desiredState);
			PebbleKit.sendDataToPebble(getApplicationContext(), alternativeAppUUID, mdata);
		}
	}
	
	
	
	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		int cmd = intent.getIntExtra("CMD", CMD_UNKNOWN);
		Log.i(TAG,"onStartCommand:  " + cmd);
		
		processCommand(cmd);
		
		return START_STICKY;
	};
	
	private void startMyTracksService() {
//		Log.i(TAG,"Start mytracks service");
		if ( myTracksService != null ) {
//			Log.i(TAG,"Service already bound");
			return;
		}
		// for the MyTracks service
	    Intent intent = new Intent();
	    ComponentName componentName = new ComponentName(getString(R.string.mytracks_service_package), getString(R.string.mytracks_service_class));
	    intent.setComponent(componentName);
	    startService(intent);
	    bindService(intent, serviceConnection, 0);
	    
	    
	    
	}

	private void stopMyTracksService() {
		Log.i(TAG,"Stop mytracks service");
		if ( myTracksService != null ) {
			Log.i(TAG,"unbinding service");
		    unbindService(serviceConnection);
		    myTracksService = null;
		}
				
	    Intent intent = new Intent();
	    ComponentName componentName = new ComponentName(getString(R.string.mytracks_service_package), getString(R.string.mytracks_service_class));
	    intent.setComponent(componentName);
	    stopService(intent);	    
	}

	private void initSportsData() {
		Log.i(TAG,"init sports data");
		try {
			Location loc = myTracksProviderUtils.getLastValidTrackPoint();
			TripStatistics statistics = myTracksProviderUtils.getLastTrack().getTripStatistics();
			long trackid = myTracksProviderUtils.getLastTrack().getId();

			sportsData.setFakeStartTime(statistics.getStartTime());

			// initialise odometer
			double odometer = 0;
			Iterator<Track> trackIterator = myTracksProviderUtils.getAllTracks().iterator();
			while ( trackIterator.hasNext()) {
				odometer += trackIterator.next().getTripStatistics().getTotalDistance();
				sportsData.setOdometer(odometer);
			}

			
		} catch ( Exception e ) {
			Log.i(TAG,"Exception during update data" + e.getMessage());
		}
		Log.i(TAG,"finished init sports data");
		
	}
	
	private void updateMyTracks() {
		Log.i(TAG,"updateMyTracks started");
		try {
			startMyTracksService();
			if ( myTracksService != null ) {
				
				if ( myTracksService.isRecording()) {
					if ( myTracksService.isPaused() ) {
						currentState = STATE_MYTRACKS_PAUSED;										
						Log.i(TAG,"Current state = STATE_MYTRACKS_PAUSED");
					} else {
						Log.i(TAG,"Current state = STATE_MYTRACKS_RECORDING");
						currentState = STATE_MYTRACKS_RECORDING;																
					}
				} else {
					Log.i(TAG,"Current state = STATE_MYTRACKS_NOTHING");
					currentState = STATE_MYTRACKS_NOTHING;
				}

				if ( currentCommand != CMD_UNKNOWN ) {
					switch ( currentCommand ) {
					case CMD_START_TRACK:
						if (( currentState == STATE_MYTRACKS_NOTHING )&&(desiredState == STATE_MYTRACKS_RECORDING)) {
							myTracksService.startNewTrack();
						}
						break;
					case CMD_STOP_TRACK:
						if ((currentState == STATE_MYTRACKS_RECORDING)&&(desiredState == STATE_MYTRACKS_NOTHING )) {
							Log.i(TAG,"Stopping track recording");
							myTracksService.endCurrentTrack();
						}
						if ((currentState == STATE_MYTRACKS_PAUSED)&&(desiredState == STATE_MYTRACKS_NOTHING)) {
							myTracksService.endCurrentTrack();
							currentCommand = CMD_UNKNOWN;
						}
						break;
					case CMD_PAUSE_TRACK:
						if ((currentState == STATE_MYTRACKS_RECORDING)&&(desiredState == STATE_MYTRACKS_PAUSED )) {
							myTracksService.pauseCurrentTrack();
						}
						break;
					case CMD_RESUME_TRACK:
						if ((currentState == STATE_MYTRACKS_PAUSED)&&(desiredState == STATE_MYTRACKS_RECORDING )) {
							myTracksService.resumeCurrentTrack();
						}
						break;						
					}
					currentCommand = CMD_UNKNOWN;
				}
				
							
				if ( currentState == desiredState ) {					
					if ( currentState == STATE_MYTRACKS_NOTHING ) {
						stopUpdater();
					}						
					if ( currentState == STATE_MYTRACKS_PAUSED ) {
						stopUpdater();
					}						
				}
			}
		} catch ( Exception e ) {
			
		}

	}
	
	private void updateSportsData() {
		Log.i(TAG,"updateSportsData");
		try {
			Location loc = myTracksProviderUtils.getLastValidTrackPoint();
			TripStatistics statistics = myTracksProviderUtils.getLastTrack().getTripStatistics();


			long trackid = myTracksProviderUtils.getLastTrack().getId();
			Location startLocation = myTracksProviderUtils.getFirstValidTrackPoint(trackid);
			
			

			
			if ( startLocation != null ) {
				sportsData.setDistanceToStart(loc.distanceTo(startLocation));
			} else {
				sportsData.setDistanceToStart(0);
			}
			sportsData.setAltitude(loc.getAltitude());
			sportsData.setStartTime(statistics.getStartTime());
			sportsData.setStopTime(statistics.getStopTime());
			sportsData.setMaxaltitude(statistics.getMaxElevation());
			sportsData.setAvgmovingspeed(statistics.getAverageMovingSpeed());
			sportsData.setAvgspeed(statistics.getAverageSpeed());
			sportsData.setBearing(loc.getBearing());
			sportsData.setTotalmovingtime(statistics.getMovingTime());
			
			
			sportsData.setTotaltime(statistics.getTotalTime());
			sportsData.setTotalTimeFromStart(System.currentTimeMillis() - statistics.getStartTime());
			
			sportsData.setDistance(statistics.getTotalDistance());
			sportsData.setTotalelevation(statistics.getTotalElevationGain());

			
			sportsData.setSpeed(0);
			if (( loc.getTime() - 10000 < sportsData.getLocationTime())&&( loc.hasSpeed())) {
				sportsData.setSpeed(loc.getSpeed());
			}
			sportsData.setLocationTime(loc.getTime());

			
			
		} catch ( Exception e ) {
			Log.i(TAG,"Exception during update data: " + e.getMessage());
		}
		Log.i(TAG,"updateSportsData: Done");
	}
	
	private void updateMyApp() {
		Log.i(TAG,"Display at pebble called");

		String values = "";
		String valueNames = "";
		for(int i=0;(i<MyAppSettings.numFields);i++) {
			ParameterType type = myAppSettings.getParameter(i);

			if ( i > 0 ) {
				values += ";";
				valueNames += ";";
			}
			
			valueNames += type.getPebbleString();
			
			switch ( type ) {
			case SPEED:
				values += String.format("%.1f",sportsData.getSpeed() * (metricUnits ? SportsData.MPS_TO_KMH : SportsData.MPS_TO_MPH));
				break;
			case DISTANCE:
			{
				double distance = sportsData.getDistance() * (metricUnits ? SportsData.M_TO_KM : SportsData.M_TO_MILE);
				if ( distance > 100 ) {
					values += String.format("%.0f",distance);
				} else {
					values += String.format("%.1f",distance);
				}
			}
			break;
			case AVGSPEED:
				values += String.format("%.1f",sportsData.getAvgspeed() * (metricUnits ? SportsData.MPS_TO_KMH : SportsData.MPS_TO_MPH));
				break;
			case AVGMOVINGSPEED:
				values += String.format("%.1f",sportsData.getAvgmovingspeed() * (metricUnits ? SportsData.MPS_TO_KMH : SportsData.MPS_TO_MPH));
				break;
			case TOTALMOVINGTIME:
			{
				long time = sportsData.getTotalmovingtime() / 1000;
				if ( time > 3599 ) {
					values += String.format("%02d:%02d",time/3600,(time%3600)/60);
				} else {
					values += String.format("%02d:%02d",time/60,time%60);                  
				}
			}
			break;
			case TOTALTIMEFROMSTART:
			{
				long time = sportsData.getTotalTimeFromStart() / 1000;
				if ( time > 3599 ) {
					values += String.format("%02d:%02d",time/3600,(time%3600)/60);
				} else {
					values += String.format("%02d:%02d",time/60,time%60);                  
				}
			}
			break;
			case TOTALTIME:
			{
				long time = sportsData.getTotaltime() / 1000;
				if ( time > 3599 ) {
					values += String.format("%02d:%02d",time/3600,(time%3600)/60);
				} else {
					values += String.format("%02d:%02d",time/60,time%60);                  
				}
			}
			break;
			case NOTHING:
				values += "-";
				break;
			case DISTANCETOSTART:
			{
				double distanceToStart = sportsData.getDistanceToStart() * (metricUnits ? SportsData.M_TO_KM : SportsData.M_TO_MILE);
				if ( distanceToStart > 100 ) {
					values += String.format("%.0f",distanceToStart);    	  
				} else { 
					values += String.format("%.1f",distanceToStart);    	      	  
				}
			}	
			break;
			case ELEVATIONGAIN:
			{	
				double elevationgain = sportsData.getElevationGain() * (metricUnits ? 1 : SportsData.M_TO_FEET);
				if ( elevationgain > 1000 ) {
					values += String.format("%.2f", elevationgain / 1000);
				} else {
					values += String.format("%.0f", elevationgain);					
				}
			}
			break;
			default:
				values += "0.0";
				break;
			}
		}

		if ( PebbleKit.isWatchConnected(this)) {
			PebbleDictionary mdata = new PebbleDictionary();

			if ( sendConfig > 0 ) {
//				Log.i(TAG,"Value Names: " + valueNames);
				mdata.addString(MSG_SET_NAMES, valueNames);
				sendConfig--;
			}

			mdata.addString(MSG_SET_VALUES, values);
			mdata.addInt8(MSG_SET_CURRENTSTATE, currentState);
			mdata.addInt8(MSG_SET_DESIREDSTATE, desiredState);
			PebbleKit.sendDataToPebble(getApplicationContext(), alternativeAppUUID, mdata);
		}
	}


	private void updatePebbleSportsApp() {
		Log.i(TAG,"UpdatePebbleSportsApp");
		PebbleDictionary data = new PebbleDictionary();

		
		long time = sportsData.getTotaltime() / 1000;

		if ( time > 3599 ) {
			data.addString(Constants.SPORTS_TIME_KEY,String.format("%02d:%02d:%02d",time/3600,(time%3600)/60,time%60));
		} else {
			data.addString(Constants.SPORTS_TIME_KEY,String.format("%02d:%02d",time/60,time%60));                  
		}

		double speed = sportsData.getSpeed();
		double totalDistance = sportsData.getDistance() * SportsData.M_TO_KM;
		if ( metricUnits ) {
			speed = speed * SportsData.MPS_TO_KMH;
			data.addUint8(Constants.SPORTS_UNITS_KEY, (byte)Constants.SPORTS_UNITS_METRIC);
			data.addUint8(Constants.SPORTS_LABEL_KEY, (byte)Constants.SPORTS_DATA_SPEED);
		} else {
			speed = speed * SportsData.MPS_TO_MPH;
			totalDistance = totalDistance * SportsData.KM_TO_MILES;
			data.addUint8(Constants.SPORTS_UNITS_KEY, (byte)Constants.SPORTS_UNITS_IMPERIAL);        
			data.addUint8(Constants.SPORTS_LABEL_KEY, (byte)Constants.SPORTS_DATA_SPEED);
		}


		if ( totalDistance > 100 ) {
			data.addString(Constants.SPORTS_DISTANCE_KEY, String.format("%.0f", totalDistance));
		} else if ( totalDistance > 10 ) {
			data.addString(Constants.SPORTS_DISTANCE_KEY, String.format("%.1f", totalDistance));
		} else {
			data.addString(Constants.SPORTS_DISTANCE_KEY, String.format("%.2f", totalDistance));
		}
		data.addString(Constants.SPORTS_DATA_KEY, String.format("%.1f",speed));


		PebbleKit.sendDataToPebble(getApplicationContext(), appUUID, data);

		Log.i(TAG,"UpdatePebbleSportsApp: done");

	}



	@Override
	public IBinder onBind(android.content.Intent intent) {
		Log.i(TAG, "On Bind");
		return null;
	}

	// Send a broadcast to launch the specified application on the connected Pebble
	public void startWatchApp() {
		Log.i(TAG,"startWatchApp");

		PebbleKit.startAppOnPebble(getApplicationContext(), appUUID);
	}

	// Send a broadcast to close the specified application on the connected Pebble
	public void stopWatchApp() {
		Log.i(TAG,"stopWatchApp");
		PebbleKit.closeAppOnPebble(getApplicationContext(), appUUID);
	}

	// A custom icon and name can be applied to the sports-app to
	// provide some support for "branding" your Pebble-enabled sports
	// application on the watch.
	//
	// It is recommended that applications customize the sports
	// application before launching it. Only one application may
	// customize the sports application at a time on a first-come,
	// first-serve basis.
	public void customizeWatchApp() {
		Log.i(TAG,"customizeWatchApp");
		final String customAppName = "My Sports App";
		final Bitmap customIcon = BitmapFactory.decodeResource(getResources(), R.drawable.watch);

		PebbleKit.customizeWatchApp(getApplicationContext(), Constants.PebbleAppType.SPORTS, customAppName, customIcon);

	}

}
