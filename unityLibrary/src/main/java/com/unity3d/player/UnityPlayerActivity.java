package com.unity3d.player;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PixelFormat;
import android.hardware.Camera;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.os.Process;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.google.gson.Gson;
import com.unity3d.cammrad_nurse.R;
import com.unity3d.player.objects.CameraPreview;


import org.jetbrains.annotations.NotNull;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class UnityPlayerActivity extends Activity implements IUnityPlayerLifecycleEvents
{

    private SpeechRecognizer speechRecognizer;

    TextToSpeech t1;

    Intent speechRecognizerIntent;

    boolean listening = false;

    private Camera camera;

    private String mFileName = null;

    private boolean continueRecording = true;

    private boolean isListeningForQuestion = false, isProcessingUserCommand = false;
    private boolean isDoingRationale = false;

    private int audioRound = 0;

    OkHttpClient client;

    private static final int PERMISSIONS_REQUEST_RECORD_AUDIO = 1;

    private static final int CAMERA_MIC_PERMISSION_REQUEST_CODE = 1;

    protected UnityPlayer mUnityPlayer; // don't change the name of this variable; referenced from native code

    // Override this in your custom UnityPlayerActivity to tweak the command line arguments passed to the Unity Android Player
    // The command line arguments are passed as a string, separated by spaces
    // UnityPlayerActivity calls this from 'onCreate'
    // Supported: -force-gles20, -force-gles30, -force-gles31, -force-gles31aep, -force-gles32, -force-gles, -force-vulkan
    // See https://docs.unity3d.com/Manual/CommandLineArguments.html
    // @param cmdLine the current command line arguments, may be null
    // @return the modified command line string or null
    protected String updateUnityCommandLineArguments(String cmdLine)
    {
        return cmdLine;
    }

    // Setup activity layout
    @Override protected void onCreate(Bundle savedInstanceState)
    {

        //check for microphone and camera permission
        if (!checkPermissionForCameraAndMicrophone()) {
            Log.d("Saboor", "Needs permissions");
            requestPermissionForCameraAndMicrophone();
        } else {
            Log.d("Saboor", "Has permissions");
        }

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(savedInstanceState);

        String cmdLine = updateUnityCommandLineArguments(getIntent().getStringExtra("unity"));
        getIntent().putExtra("unity", cmdLine);

        mUnityPlayer = new UnityPlayer(this, this);

        setContentView(R.layout.activity_unity);

        //initialize camera with no preview
        camera = Camera.open();
        CameraPreview cameraPreview = new CameraPreview(this, camera);

        // preview is required. But you can just cover it up in the layout.
        FrameLayout previewFL = findViewById(R.id.preview_layout);
        previewFL.addView(cameraPreview);
        camera.startPreview();

        FrameLayout frameLayout = findViewById(R.id.root);
        frameLayout.addView(mUnityPlayer);
        mUnityPlayer.setSoundEffectsEnabled(false);
        mUnityPlayer.requestFocus();

        client = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build();


        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);

        speechRecognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());

        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle bundle) {

            }

            @Override
            public void onBeginningOfSpeech() {
                Log.d("Saboor", "Listening");
            }

            @Override
            public void onRmsChanged(float v) {

            }

            @Override
            public void onBufferReceived(byte[] bytes) {

            }

            @Override
            public void onEndOfSpeech() {
                listening=false;
            }

            @Override
            public void onError(int i) {

            }

            @Override
            public void onResults(Bundle bundle) {
                ArrayList<String> data = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);

                String text = data.get(0);

                if(!isDoingRationale && !isListeningForQuestion && text.toLowerCase().contains("have a question")){
                    isProcessingUserCommand=true;
                    isListeningForQuestion = true;
                    readStep("Ok. I'm listening");

                    new Handler().postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            isProcessingUserCommand=false;
                        }
                    },2500);


                }else if(isListeningForQuestion){

                    readStep("Give me a sec");

                    isProcessingUserCommand=true;
                    Log.d("Saboor", "Results: " + data.get(0));

                    JSONObject jsonObject = new JSONObject();
                    try {
                        jsonObject.put("question",text);
                    } catch (JSONException e) {
                        throw new RuntimeException(e);
                    }

                    RequestBody requestJsonBody = RequestBody.create(
                            jsonObject.toString(),
                            MediaType.parse("application/json")
                    );

                    Request request2 = new Request.Builder()
                            .url("https://e6b2f4466d4c.ngrok.app/jini/ask")
                            .post(requestJsonBody)
                            .build();

                    client.newCall(request2).enqueue(new Callback() {
                        @Override
                        public void onFailure(@NotNull Call call, @NotNull IOException e) {
                            Log.d("Saboor", "Second Request failed");
                            e.printStackTrace();
                            isListeningForQuestion=false;
                            isProcessingUserCommand=false;
                        }

                        @Override
                        public void onResponse(@NotNull Call call, @NotNull Response response) throws IOException {

                            String rep = response.body().string();

                            readStep(rep.replaceAll("\\R", "").replaceAll("[^A-Za-z0-9]",""));

                            Log.d("Saboor", "Data saved to scribar");
                            Log.d("Saboor", rep);

                            isListeningForQuestion=false;
                            isProcessingUserCommand=false;

                        }
                    });

                }else if(!isDoingRationale && !isListeningForQuestion && !text.toLowerCase().contains("have a question")){
                    //interprate normal commands

                    if(text.toLowerCase().contains("next") || text.toLowerCase().contains("done") || text.toLowerCase().contains("dunn")){

                        //t1.speak("Ok. Going to next step", TextToSpeech.QUEUE_FLUSH, null);
                        mUnityPlayer.UnitySendMessage("CAMMRADPMController", "next", "");
                        takePicture();


                    }else if(text.toLowerCase().contains("back")){
                        //t1.speak("Ok. Going to previous step", TextToSpeech.QUEUE_FLUSH, null);
                        mUnityPlayer.UnitySendMessage("CAMMRADPMController", "back", "");
                        takePicture();

                    }else if(text.toLowerCase().contains("completed task") || text.toLowerCase().contains("task complete") || text.toLowerCase().contains("I'm finished")){
                        t1.speak("Ending task", TextToSpeech.QUEUE_FLUSH, null);
                        mUnityPlayer.UnitySendMessage("CAMMRADPMController", "finishTask", "");
                        takePicture();

                    }else if(text.toLowerCase().contains("start task")){
                        t1.speak("Starting task", TextToSpeech.QUEUE_FLUSH, null);
                        mUnityPlayer.UnitySendMessage("MainController", "setCurrentTask", "1");

                    }
                }else if(isDoingRationale){

                    if(text.toLowerCase().contains("repeat the question")){
                        mUnityPlayer.UnitySendMessage("Rationale Training Controller", "readQuestion", "");
                    }else {
                        mUnityPlayer.UnitySendMessage("Rationale Training Controller", "saveAnswer", text);
                    }
                }




                /*
                //if(text.toLowerCase().contains("jenny")){
                    if(text.toLowerCase().contains("next") || text.toLowerCase().contains("done") || text.toLowerCase().contains("dunn")){

                        //t1.speak("Ok. Going to next step", TextToSpeech.QUEUE_FLUSH, null);
                        mUnityPlayer.UnitySendMessage("CAMMRADPMController", "next", "");
                        takePicture();


                    }else if(text.toLowerCase().contains("back")){
                        //t1.speak("Ok. Going to previous step", TextToSpeech.QUEUE_FLUSH, null);
                        mUnityPlayer.UnitySendMessage("CAMMRADPMController", "back", "");
                        takePicture();

                    }else if(text.toLowerCase().contains("completed task") || text.toLowerCase().contains("task complete") || text.toLowerCase().contains("I'm finished")){
                        t1.speak("Ending task", TextToSpeech.QUEUE_FLUSH, null);
                        mUnityPlayer.UnitySendMessage("CAMMRADPMController", "finishTask", "");
                        takePicture();

                    }else if(text.toLowerCase().contains("start task")){
                        t1.speak("Starting task", TextToSpeech.QUEUE_FLUSH, null);
                        mUnityPlayer.UnitySendMessage("MainController", "setCurrentTask", "1");

                    }
                //}

                 */

            }

            @Override
            public void onPartialResults(Bundle bundle) {

            }

            @Override
            public void onEvent(int i, Bundle bundle) {

            }
        });

        t1=new TextToSpeech(getApplicationContext(), new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if(status != TextToSpeech.ERROR) {
                    t1.setLanguage(Locale.ENGLISH);
                }
            }
        });

    }

    public void startRationaleQuestions(String empty){
        Log.d("Saboor", "Starting rationale questions");
        this.isDoingRationale=true;
    }

    public void stopRationaleQuestions(){
        this.isDoingRationale=false;
    }

    public void readStep(String text){
        Log.d("Saboor", text);
        t1.speak(text, TextToSpeech.QUEUE_ADD, null, null);
    }

    //Start taking pictures continuosly in 4 second intervals
    public void takePicture(){

        //Take a picture with the camera

        try {
            camera.takePicture(null, null, new Camera.PictureCallback() {
                @Override
                public void onPictureTaken(byte[] data, Camera camera) {

                    if (data != null) {

                        camera.startPreview();

                        processPicture(data);

                    }
                }
            });
        }catch(Exception e){
            System.out.println("Error taking picture");
        }

    }


    public void processPicture(byte[] data){

        try{

            //convert raw data to bitmap
            Bitmap bitmap = BitmapFactory.decodeByteArray(data , 0, data .length);

            Log.d("Saboor", "height of image was " +  bitmap.getHeight());
            Log.d("Saboor", "size of image was " +  bitmap.getByteCount());



            //generate random name for image file
            Date now = new Date();
            String mPath = getExternalCacheDir().toString() + "/image" + now.getTime() + ".jpg";
            File imageFile = new File(mPath);
            FileOutputStream outputStream = new FileOutputStream(imageFile);
            int quality = 50;

            //compress to jpeg
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream);

            outputStream.flush();

            //close out put stream
            outputStream.close();

            Log.d("Saboor", "Uploading file" + imageFile.getName());
            Log.d("Saboor", "size of image was after compression" +  imageFile.length());


            //Upload file to backend python application
            RequestBody requestBody = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", imageFile.getName(),
                            RequestBody.create(
                                    imageFile,
                                    MediaType.parse("image/jpeg")))
                    .build();

            Request request = new Request.Builder()
                    .url("https://scribar.herokuapp.com/api/file/upload/screenshot")
                    .post(requestBody)
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NotNull Call call, @NotNull IOException e) {
                    Log.d("Saboor", "Request failed to upload image");
                    e.printStackTrace();
                }

                @Override
                public void onResponse(@NotNull Call call, @NotNull Response response) throws IOException {

                    String rep = response.body().string();
                    Log.d("Saboor", "Image uploaded successfully");
                    Log.d("Saboor", rep);

                }
            });

        } catch (Throwable e) {
            // Several error may come out with file handling or DOM
            e.printStackTrace();
            Log.d("SCREENSHOT", "ERROR!!!");

        }

    }



    private boolean checkPermissionForCameraAndMicrophone() {
        int resultExternalStorage = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE);
        int resultCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA);
        int resultMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO);
        return resultCamera == PackageManager.PERMISSION_GRANTED &&
                resultExternalStorage == PackageManager.PERMISSION_GRANTED &&
                resultMic == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissionForCameraAndMicrophone() {
        if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ||
                ActivityCompat.shouldShowRequestPermissionRationale(this,
                        Manifest.permission.RECORD_AUDIO)
                || ActivityCompat.shouldShowRequestPermissionRationale(this,
                Manifest.permission.CAMERA)) {
            Toast.makeText(this,
                    "Missing permissions",
                    Toast.LENGTH_LONG).show();
        } else {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA},
                    CAMERA_MIC_PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSIONS_REQUEST_RECORD_AUDIO) {
            boolean cameraAndMicPermissionGranted = true;

            for (int grantResult : grantResults) {
                cameraAndMicPermissionGranted &= grantResult == PackageManager.PERMISSION_GRANTED;
            }

            if (cameraAndMicPermissionGranted) {

            }
        }

    }


    // When Unity player unloaded move task to background
    @Override public void onUnityPlayerUnloaded() {
        moveTaskToBack(true);
    }

    // Callback before Unity player process is killed
    @Override public void onUnityPlayerQuitted() {
    }

    @Override protected void onNewIntent(Intent intent)
    {
        // To support deep linking, we need to make sure that the client can get access to
        // the last sent intent. The clients access this through a JNI api that allows them
        // to get the intent set on launch. To update that after launch we have to manually
        // replace the intent with the one caught here.
        setIntent(intent);
        mUnityPlayer.newIntent(intent);
    }

    // Quit Unity
    @Override protected void onDestroy ()
    {
        mUnityPlayer.destroy();
        super.onDestroy();
    }

    // If the activity is in multi window mode or resizing the activity is allowed we will use
    // onStart/onStop (the visibility callbacks) to determine when to pause/resume.
    // Otherwise it will be done in onPause/onResume as Unity has done historically to preserve
    // existing behavior.
    @Override protected void onStop()
    {
        super.onStop();
        mUnityPlayer.onStop();
    }

    @Override protected void onStart()
    {
        super.onStart();
        mUnityPlayer.onStart();
    }

    // Pause Unity
    @Override protected void onPause()
    {
        super.onPause();
        mUnityPlayer.onPause();
    }

    // Resume Unity
    @Override protected void onResume()
    {
        super.onResume();
        mUnityPlayer.onResume();
    }

    // Low Memory Unity
    @Override public void onLowMemory()
    {
        super.onLowMemory();
        mUnityPlayer.lowMemory();
    }

    // Trim Memory Unity
    @Override public void onTrimMemory(int level)
    {
        super.onTrimMemory(level);
        if (level == TRIM_MEMORY_RUNNING_CRITICAL)
        {
            mUnityPlayer.lowMemory();
        }
    }

    // This ensures the layout will be correct.
    @Override public void onConfigurationChanged(Configuration newConfig)
    {
        super.onConfigurationChanged(newConfig);
        mUnityPlayer.configurationChanged(newConfig);
    }

    // Notify Unity of the focus change.
    @Override public void onWindowFocusChanged(boolean hasFocus)
    {
        super.onWindowFocusChanged(hasFocus);
        mUnityPlayer.windowFocusChanged(hasFocus);
    }

    // For some reason the multiple keyevent type is not supported by the ndk.
    // Force event injection by overriding dispatchKeyEvent().
    @Override public boolean dispatchKeyEvent(KeyEvent event)
    {
        if (event.getAction() == KeyEvent.ACTION_MULTIPLE)
            return mUnityPlayer.injectEvent(event);
        return super.dispatchKeyEvent(event);
    }

    public void listenForUserSpeech(){

        Log.d("Saboor", "Listening for user question");

        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                speechRecognizer.startListening(speechRecognizerIntent);
                listening=true;
            }
        },100);



        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                speechRecognizer.stopListening();
                listening=false;
            }
        },isDoingRationale?12000:8000);

    }

    // Pass any events not handled by (unfocused) views straight to UnityPlayer
    @Override public boolean onKeyUp(int keyCode, KeyEvent event)     {
        Log.d("Saboor", "Key was pressed: " + keyCode);

        if(keyCode == 24){

            if(!isProcessingUserCommand) {

                Log.d("Saboor", "Calling method listenForUserQuestion");

                listenForUserSpeech();

            }
        }

        /*
        if(keyCode == 119 && !listening){
            speechRecognizer.startListening(speechRecognizerIntent);
            listening=true;

            long millisecDelay=3000;

            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    speechRecognizer.stopListening();
                    listening=false;
                }
            },8000);

            //Stop after 20 seconds
        }
         */

        return mUnityPlayer.onKeyUp(keyCode, event);
    }
    @Override public boolean onKeyDown(int keyCode, KeyEvent event)   {


        if(keyCode == 119){
            //speechRecognizer.startListening(speechRecognizerIntent);
        }

        return mUnityPlayer.onKeyDown(keyCode, event);
    }
    @Override public boolean onTouchEvent(MotionEvent event)          { return mUnityPlayer.onTouchEvent(event); }
    @Override public boolean onGenericMotionEvent(MotionEvent event)  { return mUnityPlayer.onGenericMotionEvent(event); }
}