package org.ompt.omptcam;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.text.DateFormat;
import java.text.SimpleDateFormat;

import android.graphics.Rect;
import android.hardware.Camera;
import android.hardware.Camera.AutoFocusCallback;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.MediaStore;
import android.annotation.TargetApi;
import android.app.Activity;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.util.Log;
import android.view.KeyEvent;
import android.view.OrientationEventListener;
import android.view.Surface;
//import android.view.InputDevice;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.view.MotionEvent;

import com.trihedraltutoring.campreview.R;

public class MainActivity extends Activity {
    Camera cam1;
    CameraPreview camView;
    MediaRecorder mRecorder;
    private int oldAngle;
    private int currentMic;
    private int currentCam;
    private int seconds;
    private int numCams;
    private int scoState;
    private boolean btConnected;
    private boolean isRecording;
    private boolean settingsOpen;
    private boolean isPaused;
    private boolean viewAvailable;
    private boolean inLandscape;
    private Handler handler;
    private OrientationEventListener orientationEventListener;
    Camera.Parameters params;
    TextView timerView, recView;
    File vidPath;
    File vidFile;
    private String fName;
    private boolean focusing;
    AudioManager btManager;
    BluetoothReceiver btReceiver;
    DeviceReceiver hwReceiver;
    CamcorderProfile camProfile;
    //private InputDevice touchScreen; // to get screen info on init?

    /*********************************** SETUP ***********************************/
    /*****************************************************************************/
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        initialize();
    }
    @Override
    protected void onPause() {
        super.onPause();
        releaseMediaRecorder();
        releaseCamera();
        btManager.stopBluetoothSco();
        unregisterReceiver(btReceiver);
        isPaused = true;
        if (isRecording) vidFile.delete();
    }
    protected void onResume () {
        super.onResume();
        if (isPaused) initialize();
    }
    private void initialize(){
        setContentView(R.layout.activity_main);
        currentMic = MediaRecorder.AudioSource.DEFAULT;
        currentCam = MediaRecorder.VideoSource.DEFAULT;
        numCams    = Camera.getNumberOfCameras();

        // Set vid file directory, creating it if necessary //
        //vidPath = new File(Environment.getExternalStorageDirectory().getPath(), "OMPT Cam");
        vidPath = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DCIM), "OMPT Cam");

        vidPath.mkdirs();

        handler = new Handler();
        prepareCamera();
        prepareBtConnection();
        btManager.startBluetoothSco();  // default to BT if available
        // Create our Preview view and set it as the content of our activity.
        camView = new CameraPreview(this, cam1, currentCam, params, camProfile);
        FrameLayout frameLayout = (FrameLayout) findViewById(R.id.camera_preview);
        frameLayout.addView(camView);
        // add buttons above camView //
        RelativeLayout relativeLayout1 = (RelativeLayout) findViewById(R.id.buttons_layout);
        relativeLayout1.bringToFront();

        // hide view button if unsupported //
        Intent intent = new Intent(Intent.ACTION_VIEW,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI);
        if (intent.resolveActivity(getPackageManager()) == null) {
            setViewVisibility(R.id.button_view, 0);
            viewAvailable = false;
        }
        else viewAvailable = true;

        orientationEventListener = new OrientationEventListener(this){
            @Override
            public void onOrientationChanged(int angle){

                Log.d("INFO", "Angle: " + angle);
                if (angle >= 45 && angle <= 315){
                    if (angle >= 45 && oldAngle < 45) {
                        Log.d("INFO", "Reverse Landscape Mode");
                        inLandscape = false;
                    }
                    else if (angle <= 315 && oldAngle > 315){
                        Log.d("INFO", "Landscape Mode");
                        inLandscape = true;
                    }
                    /**
                     // Brute force rotation. Replace by invoking camView's surfaceChanged //
                     cam1.stopPreview();
                     CameraPreview.setCameraDisplayOrientation();
                     params.setPreviewSize(camProfile.videoFrameWidth, camProfile.videoFrameHeight);
                     cam1.setParameters(params);
                     try {
                     cam1.setPreviewDisplay(camView.getHolder());
                     } catch (IOException e) {}
                     cam1.startPreview();**/
                }
                oldAngle = angle;
            }
        };
        orientationEventListener.enable();

        isPaused = false;
        seconds = 0;
        isRecording = false;
        settingsOpen = false;
        timerView = (TextView) findViewById(R.id.textView_timer);
        recView   = (TextView) findViewById(R.id.textView_rec  );
        focusing = false;
        btConnected = false;
    }
    private void reinitialize(){
        setContentView(R.layout.activity_main);
        prepareCamera();
        // Create our Preview view and set it as the content of our activity.
        camView = new CameraPreview(this, cam1, currentCam, params, camProfile);
        FrameLayout frameLayout = (FrameLayout) findViewById(R.id.camera_preview);
        frameLayout.addView(camView);
        RelativeLayout relativeLayout1 = (RelativeLayout) findViewById(R.id.buttons_layout);
        relativeLayout1.bringToFront();
        timerView = (TextView) findViewById(R.id.textView_timer);
        recView   = (TextView) findViewById(R.id.textView_rec  );
    }
    /*****************************************************************************/


    /*********************************** SYSETM **********************************/
    /*****************************************************************************/
    //// Initializes MediaRecorder mRecorder for recording from cam1 ////
    private boolean prepareMediaRecorder(){
        vidFile = getPreparedFile();
        mRecorder = new MediaRecorder();
        try{
            cam1.unlock();
        } catch (RuntimeException e) {
            Log.d("ERROR", "RuntimeException unlocking cam1: " + e.getMessage());
        }
        if (btReceiver.isConnected()){
            camProfile.audioCodec = MediaRecorder.AudioEncoder.DEFAULT;
            //camProfile.audioBitRate =
            //camProfile.audioSampleRate =
        }

        if (!inLandscape) mRecorder.setOrientationHint(270);

        mRecorder.setCamera(cam1); // applies cam1 object to mRecorder
        mRecorder.setAudioSource(currentMic);
        mRecorder.setVideoSource(currentCam);
        mRecorder.setProfile(camProfile);
        mRecorder.setOutputFile(vidFile.getAbsolutePath());
        // Set the preview output
        mRecorder.setPreviewDisplay(camView.getHolder().getSurface());
        // Prepare configured MediaRecorder
        try {
            mRecorder.prepare();
        } catch (IllegalStateException e) {
            Log.d("ERROR", "IllegalStateException preparing MediaRecorder: " + e.getMessage());
            releaseMediaRecorder();
            return false;
        } catch (IOException e) {
            Log.d("ERROR", "IOException preparing MediaRecorder: " + e.getMessage());
            releaseMediaRecorder();
            return false;
        }
        return true;
    }
    private void releaseMediaRecorder(){
        if (mRecorder != null) {
            mRecorder.reset();   // clear recorder configuration
            mRecorder.release(); // release the recorder object
            mRecorder = null;
            cam1.lock();         // lock camera for later use
        }
    }
    private void setViewVisibility(int id, int state){
        if (state == 0)
            findViewById(id).setVisibility(View.INVISIBLE);
        else if (state == 1)
            findViewById(id).setVisibility(View.VISIBLE);
    }
    private void setRecordingVisibility(int state){
        setViewVisibility(R.id.button_camera, state);
        setViewVisibility(R.id.radiogroup_settings, 0);
        setViewVisibility(R.id.button_storage, 0);
        setViewVisibility(R.id.button_settings, state);
        if (viewAvailable)
            setViewVisibility(R.id.button_view, state);
        else
            setViewVisibility(R.id.button_view, 0);
    }
    /*****************************************************************************/


    /********************************* LISTENERS *********************************/
    /*****************************************************************************/
    public void recordClicked(View v) {
        Button recordButton = (Button) v;
        if (isRecording){
            Log.d("INFO","Stop clicked");
            // re-allow screen rotations //
            //setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);

            try{
                mRecorder.stop();
            }catch (Exception e){ // user pressed 'Stop' too soon
                Log.d("ERROR","mRecorder.stop(): " + e.getMessage());
                vidFile.delete();
            }

            if (vidFile.isFile()) updateContent();
            releaseMediaRecorder();
            cam1.lock();
            recordButton.setText("Rec");
            isRecording = false;
            // show buttons //
            setRecordingVisibility(1);
        }
        else {
            /**
             // prevent screen rotation while recording //
             int rot = getWindowManager().getDefaultDisplay().getRotation();
             if (rot == Surface.ROTATION_0 || rot == Surface.ROTATION_90){
             setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
             }
             else {
             setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE);
             }**/



            // hide buttons //
            setRecordingVisibility(0);
            settingsOpen = false;

            // initialize video camera
            if (prepareMediaRecorder()) {
                mRecorder.start();
                recordButton.setText("Stop");
                isRecording = true;
                handler.postDelayed(secPassed, 1);
            }
            else { // prepare didn't work, release the camera
                releaseMediaRecorder();
                // add other cleanup?
            }
        }
    }
    public void viewClicked(View v) {
        Intent intent = new Intent(Intent.ACTION_VIEW,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI); // unsure of portability
        startActivity(intent);
    }
    public void camClicked(View v) {
        currentCam ++;
        if (currentCam >= numCams) currentCam = 0;
        Log.d("INFO","Camera: " + currentCam);

        releaseMediaRecorder();
        releaseCamera();
        if (isRecording) vidFile.delete();

        reinitialize();

    }
    public void storageClicked(View v){
        Intent intent = new Intent("android.settings.INTERNAL_STORAGE_SETTINGS");
        if (intent.resolveActivity(getPackageManager()) != null) {
            startActivity(intent);
        }
    }
    public void lightClicked(View v) {
        List <String> flashModes = params.getSupportedFlashModes();
        if (flashModes != null && flashModes.contains("torch")){
            if (params.getFlashMode() == "torch"){
                params.setFlashMode("off");
                Log.d("INFO","Turning light off");
            }
            else {
                params.setFlashMode("torch");
                Log.d("INFO","Turning light on");
            }
            cam1.setParameters(params);
            cam1.startPreview();  // Needed on some devices, but I'm re-calling this (problem?)
        }
    }
    public void focusClicked(View v) {
        ((Button)v).setVisibility(View.INVISIBLE); // disable button
        params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
        cam1.setParameters(params);
    }
    public void settingsClicked(View v) {
        if (settingsOpen){
            setViewVisibility(R.id.radiogroup_settings, 0); // hide storage settings
            setViewVisibility(R.id.button_storage, 0); // hide settings link
        }
        else {
            if (Build.VERSION.SDK_INT >= 19){
                setViewVisibility(R.id.radiogroup_settings, 1); ; // show storage settings
            }
            else{
                setViewVisibility(R.id.button_storage, 1); // show settings link
            }
        }
        settingsOpen = !settingsOpen;
    }
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (!isRecording && keyCode == KeyEvent.KEYCODE_MENU)
                settingsClicked(findViewById(R.id.button_settings));
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }
    @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        // return if API doesn't support Areas //
        if (Build.VERSION.SDK_INT < 14 || params.getMaxNumFocusAreas() < 0) return false;

        setViewVisibility(R.id.button_focus, 1); // show auto focus button
        if (focusing) return false;        // return if camera is already focusing
        params.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
        cam1.cancelAutoFocus();  // Try Removing?
        focusing = true;
        AFCallback af = new AFCallback();
        cam1.autoFocus(af);

        /// Screen Coordinate Math ///
        float x = e.getX();
        float y = e.getY();
        int xMax = (int)e.getDevice().getMotionRange(0).getMax();
        int yMax = (int)e.getDevice().getMotionRange(1).getMax();
        x = x/xMax*2000 - 1000;
        y = y/yMax*2000 - 1000;

        if (params.getMaxNumMeteringAreas() > 0){ // check that metering areas are supported
            Log.d("INFO", "Setting metering area");
            List<Camera.Area> meteringAreas = new ArrayList<Camera.Area>();
            Rect areaRect = new Rect((int)x-100, (int)y-100, (int)x+100, (int)y+100);
            meteringAreas.add(new Camera.Area(areaRect, 1000)); // set weight to 100%
            params.setMeteringAreas(meteringAreas);
        }

        if (params.getMaxNumFocusAreas() > 0){ // check that focus areas are supported
            Log.d("INFO", "Setting focus area");
            List<Camera.Area> focusAreas = new ArrayList<Camera.Area>();
            Rect areaRect = new Rect((int)x-100, (int)y-100, (int)x+100, (int)y+100);
            focusAreas.add(new Camera.Area(areaRect, 1000)); // set weight to 100%
            params.setMeteringAreas(focusAreas);
        }
        cam1.setParameters(params);

        return false;
    }
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

    }

    //// Recursive timer called once per second ////
    private Runnable secPassed = new Runnable() {
        @Override
        public void run() {
            String time;
            if (!isRecording){
                seconds = 0;
                timerView.setText("");
                recView.setText("");
            }
            else {
                int min = seconds/60;
                int sec = seconds - min*60;
                time = Integer.toString(min) + ":";
                if (sec < 10) time += "0";
                time += Integer.toString(sec);

                if (seconds % 2 == 0)
                    recView.setText("");
                else
                    recView.setText("Rec");

                seconds ++;
                timerView.setText(time);
                Log.d("INFO", time);
                handler.postDelayed(secPassed, 1000);
            }

            //if (btState == 2 && scoState == 0){
            //   btManager.startBluetoothSco();
            //}
        }
    };
    ////Recursive for connecting headset ////
    private Runnable btCheck = new Runnable() {
        @Override
        public void run() {
            Log.d("INFO", "Checking for BT");
            if (btConnected == true && scoState == 0){
                btManager.startBluetoothSco();
            }
            else
                handler.postDelayed(btCheck, 2000);
        }
    };
    public class AFCallback implements AutoFocusCallback {
        @Override
        public void onAutoFocus(boolean success, Camera camera) {
            focusing = false;
        }
    }
    /*****************************************************************************/


    /********************************** CAMERA ***********************************/
    /*****************************************************************************/
    private void   prepareCamera(){
        camProfile = CamcorderProfile.get(currentCam, CamcorderProfile.QUALITY_HIGH);
        cam1 = null;
        try {cam1 = Camera.open(currentCam);}
        catch (Exception e){}
        params = cam1.getParameters();
        List<String> focusModes = params.getSupportedFocusModes(); // gets supported focus modes
        if (focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)){
            params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            cam1.setParameters(params);
        }
    }
    private void   releaseCamera(){
        if (cam1 != null){
            cam1.release();      // release the camera for other applications
            cam1 = null;
        }
    }
    /*****************************************************************************/


    /********************************* FILE OPS **********************************/
    /*****************************************************************************/
    //// Checks if external storage is available for read/write ////
    public boolean isExternalStorageWritable() {
        String state = Environment.getExternalStorageState();
        if (Environment.MEDIA_MOUNTED.equals(state)) {
            return true;
        }
        return false;
    }
    //// Returns blank File named: "DCIM/OMPT Cam/MM-dd-yy_HH-mm-ss.mp4" ////
    public File    getPreparedFile(){
        DateFormat df = new SimpleDateFormat("MM-dd-yy_HH-mm-ss", Locale.getDefault());
        Date date = new Date();
        fName = df.format(date) + ".mp4";
        File file1 = new File(vidPath, fName);
        return file1;
    }
    //// Adds new video to media directory so it appears in gallery instantly ////
    public void    updateContent(){
        Log.d("INFO","Updating content");
        sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(vidFile)));
    }
    /*****************************************************************************/


    /********************************* BLUETOOTH *********************************/
    /*****************************************************************************/
    //// Register BT headset as audio receiver ////
    @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
    @SuppressWarnings("deprecation")
    public void  prepareBtConnection(){
        // Setup bluetooth audio manager //
        btManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        btReceiver = new BluetoothReceiver();
        if (android.os.Build.VERSION.SDK_INT >= 14)
            registerReceiver(btReceiver, new IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED));
        else
            registerReceiver(btReceiver, new IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_CHANGED));

        hwReceiver = new DeviceReceiver();
        registerReceiver(hwReceiver, new IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED));
    }
    //// Invoked by external intent soon after startBluetoothSco() call ////
    public class BluetoothReceiver extends BroadcastReceiver{
        public void onReceive(Context context, Intent intent) {
            scoState = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1);
            Log.d("INFO", "Audio SCO state: " + scoState);
            if ((AudioManager.SCO_AUDIO_STATE_DISCONNECTED == scoState))
                btConnected = false;
        }
        public boolean isConnected(){
            if ((AudioManager.SCO_AUDIO_STATE_CONNECTED == scoState))
                return true;
            else if ((AudioManager.SCO_AUDIO_STATE_DISCONNECTED == scoState)){
                btManager.stopBluetoothSco();
            }
            return false;
        }
    }

    //// Invoked by external intent when a BT device connects ////
    public class DeviceReceiver extends BroadcastReceiver {
        public void onReceive(Context context, Intent intent){
            btConnected = true;
            handler.postDelayed(btCheck, 2000);
            Log.d("INFO", "BT state: " + btConnected);
        }
    }
    /*****************************************************************************/
}

