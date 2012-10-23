package net.kismetwireless.android.pcapcapture;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

import net.kismetwireless.android.pcapcapture.FilelistFragment.FileEntry;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.TableRow;
import android.widget.TextView;

public class MainActivity extends Activity {
	String LOGTAG = "PcapCapture";

	PendingIntent mPermissionIntent;
	UsbManager mUsbManager;
	Context mContext;
	
	SharedPreferences mPreferences;

	Messenger mService = null;
	boolean mIsBound = false;

	public class deferredUsbIntent {
		UsbDevice device;
		String action;
		boolean extrapermission;
	};

	ArrayList<deferredUsbIntent> mDeferredIntents = new ArrayList<deferredUsbIntent>();

	private TextView mTextDashUsb, mTextDashUsbSmall, mTextDashFile, 
		mTextDashFileSmall, mTextDashLogControl, mTextDashChanhop,
		mTextDashChanhopSmall;
	private TableRow mRowLogControl, mRowShare;
	private ImageView mImageControl;

	private String mLogPath = "";
	private boolean mLocalLogging = false, mLogging = false, mUsbPresent = false;
	private int mLogCount = 0;
	private long mLogSize = 0;
	private String mUsbType = "", mUsbInfo = "";
	private int mLastChannel = 0;
	
	public static int PREFS_REQ = 0x1001;
	
	public static final String PREF_CHANNELHOP = "channel_hop";
	public static final String PREF_CHANNEL = "channel_lock";
	public static final String PREF_CHANPREFIX = "ch_";
	
	private boolean mChannelHop;
	private int mChannelLock;
	ArrayList<Integer> mChannelList = new ArrayList<Integer>();

	class IncomingServiceHandler extends Handler {
		@Override
		public void handleMessage(Message msg) {
			Bundle b;
			boolean updateUi = false;

			switch (msg.what) {
			case PcapService.MSG_RADIOSTATE:
				b = msg.getData();

				if (b == null)
					break;

				if (b.getBoolean(UsbSource.BNDL_RADIOPRESENT_BOOL, false)) {
					mUsbPresent = true;

					mUsbType = b.getString(UsbSource.BNDL_RADIOTYPE_STRING, "Unknown");
					mUsbInfo = b.getString(UsbSource.BNDL_RADIOINFO_STRING, "No info available");
					mLastChannel = b.getInt(UsbSource.BNDL_RADIOCHANNEL_INT, 0);
				} else {
					mUsbPresent = false;
					mUsbType = "";
					mUsbInfo = "";
					mLastChannel = 0;
				}

				updateUi = true;

				break;
			case PcapService.MSG_LOGSTATE:
				b = msg.getData();

				if (b == null)
					break;

				if (b.getBoolean(PcapService.BNDL_STATE_LOGGING_BOOL, false)) {
					mLocalLogging = true;
					mLogging = true;

					mLogPath = b.getString(PcapService.BNDL_CMD_LOGFILE_STRING);
					mLogCount = b.getInt(PcapService.BNDL_STATE_LOGFILE_PACKETS_INT, 0);
					mLogSize = b.getLong(PcapService.BNDL_STATE_LOGFILE_SIZE_LONG, 0);
				} else {
					mLocalLogging = false;
					mLogging = false;
				}

				updateUi = true;

				break;
			default:
				super.handleMessage(msg);
			}

			if (updateUi)
				doUpdateUi();
		}
	}

	final Messenger mMessenger = new Messenger(new IncomingServiceHandler());

	private ServiceConnection mConnection = new ServiceConnection() {
		public void onServiceConnected(ComponentName className, IBinder service) {
			mService = new Messenger(service);

			try {
				Message msg = Message.obtain(null, PcapService.MSG_REGISTER_CLIENT);
				msg.replyTo = mMessenger;
				mService.send(msg);

				for (deferredUsbIntent di : mDeferredIntents) 
					doSendDeferredIntent(di);

			} catch (RemoteException e) {
				// Service has crashed before we can do anything, we'll soon be
				// disconnected and reconnected, do nothing
			}
		}

		public void onServiceDisconnected(ComponentName className) {
			mService = null;
			mIsBound = false;
		}
	};

	void doBindService() {
		if (mIsBound)
			return;

		bindService(new Intent(MainActivity.this, PcapService.class), mConnection, Context.BIND_AUTO_CREATE);
		mIsBound = true;
	}

	void doKillService() {
		if (mService == null)
			return;

		if (!mIsBound)
			return;

		Message msg = Message.obtain(null, PcapService.MSG_DIE);
		msg.replyTo = mMessenger;

		try {
			mService.send(msg);
		} catch (RemoteException e) {
			Log.d(LOGTAG, "Failed to send die message: " + e);
		}
	}
	
	private void doUpdatePrefs() {
		mChannelHop = mPreferences.getBoolean(PREF_CHANNELHOP, true);
		String chpref = mPreferences.getString(PREF_CHANNEL, "11");
		mChannelLock = Integer.parseInt(chpref);
		
		mChannelList.clear();
		for (int c = 1; c <= 11; c++) {
			if (mPreferences.getBoolean(PREF_CHANPREFIX + Integer.toString(c), false)) {
				mChannelList.add(c);
			}
		}
	}

	private void doUpdateServiceprefs() {
		if (mService == null)
			return;

		Message msg = Message.obtain(null, PcapService.MSG_RECONFIGURE_PREFS);
		msg.replyTo = mMessenger;

		try {
			mService.send(msg);
		} catch (RemoteException e) {
			Log.e(LOGTAG, "Failed to send prefs message: " + e);
		}
	}

	private void doUpdateServiceLogs(String path, boolean enable) {
		if (mService == null)
			return;

		Bundle b = new Bundle();

		if (enable) {
			b.putString(PcapService.BNDL_CMD_LOGFILE_STRING, path);
			b.putBoolean(PcapService.BNDL_CMD_LOGFILE_START_BOOL, true);
		} else {
			b.putBoolean(PcapService.BNDL_CMD_LOGFILE_STOP_BOOL, true);
		}

		Message msg = Message.obtain(null, PcapService.MSG_COMMAND);
		msg.replyTo = mMessenger;
		msg.setData(b);

		try {
			mService.send(msg);
		} catch (RemoteException e) {
			Log.e(LOGTAG, "Failed to send command message: " + e);
		}
	}

	void doUnbindService() {
		if (mIsBound) {
			if (mService != null) {
				try {
					Message msg = Message.obtain(null, PcapService.MSG_UNREGISTER_CLIENT);
					msg.replyTo = mMessenger;
					mService.send(msg);
				} catch (RemoteException e) {
					// Do nothing
				}
			}
		}
	}

	void doSendDeferredIntent(deferredUsbIntent i) {
		Message msg;

		Bundle b = new Bundle();

		// Toast.makeText(mContext, "Sending deferred intent", Toast.LENGTH_SHORT).show();

		msg = Message.obtain(null, PcapService.MSG_USBINTENT);

		b.putParcelable("DEVICE", i.device);
		b.putString("ACTION", i.action);
		b.putBoolean("EXTRA", i.extrapermission);

		msg.setData(b);

		try {
			mService.send(msg);
		} catch (RemoteException e) {
			// nothing
		}
	}

	private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
		public void onReceive(Context context, Intent intent) {
			String action = intent.getAction();

			if (PcapService.ACTION_USB_PERMISSION.equals(action) ||
					UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action) ||
					UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
				synchronized (this) {
					doBindService();

					deferredUsbIntent di = new deferredUsbIntent();
					di.device = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
					di.action = action;
					di.extrapermission = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);

					if (mService == null)
						mDeferredIntents.add(di);
					else
						doSendDeferredIntent(di);
				}
			}
		}
	};

	private void doUpdateUi() {
		if (!mUsbPresent) {
			mTextDashUsb.setText("No USB device present");
			mTextDashUsbSmall.setVisibility(0);
			mTextDashUsbSmall.setText("");
		} else {
			mTextDashUsb.setText(mUsbType);
			mTextDashUsbSmall.setText(mUsbInfo);
			mTextDashUsbSmall.setVisibility(1);
		}

		if (!mLogging) {
			mTextDashFile.setText("Logging inactive");
			mTextDashFileSmall.setText("");
		} else {
			mTextDashFile.setText(mLogPath);
		}

		if (mLogCount > 0 || mLogging) {
			String sz = "0B";

			if (mLogSize < 1024) {
				sz = String.format("%dB", mLogSize);
			} else if (mLogSize < (1024 * 1024)) {
				sz = String.format("%2.2fK", ((float) mLogSize) / 1024);
			} else if (mLogSize < (1024 * 1024 * 1024)) {
				sz = String.format("%5.2fM", ((float) mLogSize) / (1024 * 1024));
			}

			mTextDashFileSmall.setText(sz + ", " + mLogCount + " packets");
		} else {
			mTextDashFileSmall.setText("");
		}

		if (!mLocalLogging) {
			mTextDashLogControl.setText("Start logging");
			mImageControl.setImageResource(R.drawable.ic_action_record);
		} else {
			mTextDashLogControl.setText("Stop logging");
			mImageControl.setImageResource(R.drawable.ic_action_stop);
		}
		
		if (mChannelHop) {
			mTextDashChanhop.setText("Channel hopping enabled");
			
			String s = "";
			for (Integer i : mChannelList)  {
				s += i;
				
				if (mChannelList.indexOf(i) != mChannelList.size() - 1)
					s += ", ";
				
				if (mLastChannel != 0)
					s += " (" + mLastChannel + ")";
			}
			
			mTextDashChanhopSmall.setText(s);
		} else {
			mTextDashChanhop.setText("Channel hopping disabled");
			mTextDashChanhopSmall.setText("Locked to channel " + mChannelLock);
		}
	}
	
	public class PcapFileTyper extends FilelistFragment.FileTyper {
		@Override
		FilelistFragment.FileEntry getEntry(File directory, String fn) {
			String pcapdetails = "No pcap data";
			try {
				 pcapdetails = PcapHelper.countPcapFile(directory.toString() + "/" + fn) + " packets";
			} catch (IOException e) {
				pcapdetails = "Error: " + e;
				Log.e(LOGTAG, "Pcap error: " + e);
			}
			FileEntry f = new FilelistFragment.FileEntry(directory, fn, 
					R.drawable.icon_wireshark, fn, pcapdetails);

			return f;
		}
	}

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		// Don't launch a second copy from the USB intent
		if (!isTaskRoot()) {
			final Intent intent = getIntent();
			final String intentAction = intent.getAction(); 
			if (intent.hasCategory(Intent.CATEGORY_LAUNCHER) && intentAction != null && intentAction.equals(Intent.ACTION_MAIN)) {
				Log.w(LOGTAG, "Main Activity is not the root.  Finishing Main Activity instead of launching.");
				finish();
				return;       
			}
		}

		mContext = this;

		mPreferences = PreferenceManager.getDefaultSharedPreferences(mContext);

		// make the directory on the sdcard
		// TODO make this not hardcoded
		File f = new File("/mnt/sdcard/pcap");
	
		if (!f.exists()) {
			f.mkdir();
		}
		
		setContentView(R.layout.activity_main);

		mTextDashUsb = (TextView) findViewById(R.id.textDashUsbDevice);
		mTextDashUsbSmall = (TextView) findViewById(R.id.textDashUsbSmall);
		mTextDashFile = (TextView) findViewById(R.id.textDashFile);
		mTextDashFileSmall = (TextView) findViewById(R.id.textDashFileSmall);
		mTextDashLogControl = (TextView) findViewById(R.id.textDashCaptureControl);
		mTextDashChanhop = (TextView) findViewById(R.id.textChannelHop);
		mTextDashChanhopSmall = (TextView) findViewById(R.id.textChannelHopSmall);

		mRowShare = (TableRow) findViewById(R.id.tableRowShare);
		mRowLogControl = (TableRow) findViewById(R.id.tableRowLogControl);

		mImageControl = (ImageView) findViewById(R.id.imageDashLogControl);

		Intent svc = new Intent(this, PcapService.class);
		startService(svc);
		doBindService();

		mPermissionIntent = PendingIntent.getBroadcast(mContext, 0, new Intent(PcapService.ACTION_USB_PERMISSION), 0);

		IntentFilter filter = new IntentFilter(PcapService.ACTION_USB_PERMISSION);
		filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
		filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
		mContext.registerReceiver(mUsbReceiver, filter);
		
		FilelistFragment list = new FilelistFragment(new File("/mnt/sdcard/pcap"), 1000);
		list.registerFiletype("cap", new PcapFileTyper());
		list.setFavorites(true);
		list.Populate();
		getFragmentManager().beginTransaction().add(R.id.fragment_filelist, list).commit();

		mRowLogControl.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				if (mLocalLogging) {
					mLocalLogging = false;
					doUpdateServiceLogs(null, false);
				} else {
					mLocalLogging = true;
					mLogPath = "/mnt/sdcard/pcap/android.cap";
					doUpdateServiceLogs(mLogPath, true);
				}

				doUpdateUi();
			}
		});

		mRowShare.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				if (mLogPath.equals(""))
					return;

				shareFileDialog();
			}
		});

		doUpdatePrefs();
		
		doUpdateUi();
	}

	@Override
	public void onNewIntent(Intent intent) {
		// Replicate USB intents that come in on the single-top task
		mUsbReceiver.onReceive(this, intent);
	}

	@Override
	public void onDestroy() {
		super.onDestroy();

		mContext.unregisterReceiver(mUsbReceiver);

		doUnbindService();
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.activity_main, menu);
		return true;
	}
	
	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case R.id.menu_settings:
			startActivityForResult(new Intent(this, ChannelPrefs.class), PREFS_REQ);
			break;
		}
		
		return true;
	}
	
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		if (requestCode == PREFS_REQ) {
			doUpdateUi();
			doUpdateServiceprefs();
		}

	}

	
	private void shareFile() {
		Intent i = new Intent(Intent.ACTION_SEND); 
		i.setType("application/cap"); 
		// i.setType("application/binary");
		i.putExtra(Intent.EXTRA_STREAM, Uri.parse("file://" + mLogPath)); 
		startActivity(Intent.createChooser(i, "Share Pcap"));
	}

	public void shareFileDialog() {
		if (mLocalLogging) {
			AlertDialog.Builder alertbox = new AlertDialog.Builder(mContext);

			alertbox.setTitle("Pcap in progress");

			alertbox.setMessage("Pcap currently in progress.  While you may share " +
					"the file in progress, it may not include the most recently " +
					"captured packets.");

			alertbox.setNegativeButton("Don't Share", new DialogInterface.OnClickListener() {
				public void onClick(DialogInterface arg0, int arg1) {

				}
			});

			alertbox.setPositiveButton("Share", new DialogInterface.OnClickListener() {
				public void onClick(DialogInterface arg0, int arg1) {
					shareFile();
				}
			});

			alertbox.show();
		} else {
			shareFile();
		}
	}

}
