package com.unity3d.player;

import android.os.Handler;
import android.util.Log;

import org.jetbrains.annotations.NotNull;
import org.json.JSONException;
import org.json.JSONObject;
import org.vosk.android.SpeechService;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class JiniInterpreter {

    UnityPlayer mUnityPlayer;
    SpeechService speechService;

    OkHttpClient client;

    // Possible wake words
    private static final List<String> WAKE_WORDS = Arrays.asList("jenny", "ginny", "jimmy", "ginia", "genie");

    String[][] replacements = {
            {"ivy", "IV"},
            {"ivy league", "IV"}
    };

    // State tracking
    private boolean isListeningForCommand = false, isProcessingUserCommand=false, isDoingRationale = false;

    public JiniInterpreter(UnityPlayer mUnityPlayer, SpeechService speechService, OkHttpClient client
    ) {
        this.mUnityPlayer = mUnityPlayer;
        this.speechService = speechService;
        this.client = client;

    }

    //main method for handling user's speech to jini
    public void interpret(String hypothesis){

        if (hypothesis == null || hypothesis.trim().isEmpty() || isProcessingUserCommand) return;

        String input = hypothesis.trim().toLowerCase();

        Log.d("Saboor", "Heard: " + input);

        if(isDoingRationale) {
            processRationaleCommands(input);
            return;
        }

        if (!isListeningForCommand) {

            // Listen for wakeup command
            for (String wakeWord : WAKE_WORDS) {
                Log.d("Saboor", "Comparing: " + wakeWord + " with: " + input);
                if (input.equals(wakeWord)) {
                    speak("I'm listening.");
                    isListeningForCommand = true;
                    return;
                }
            }
            System.out.println("Wake up word not detected.");
        } else {
            // Handle command
            processCommand(input);
            isListeningForCommand = false;
            mUnityPlayer.UnitySendMessage("MainController", "hideListening", "");
        }

    }

    //call jini to speak a sentence
    public void speak(String text){
        mUnityPlayer.UnitySendMessage("MainController", "speak", text);

    }

    //process commands for rationale portion
    private void processRationaleCommands(String input){
        if (input.contains("repeat") && input.contains("question")) {
            mUnityPlayer.UnitySendMessage("Rationale Training Controller", "readQuestion", "");
        } else {
            mUnityPlayer.UnitySendMessage("Rationale Training Controller", "saveAnswer", input);
        }
    }

    //process general commands
    private void processCommand(String command) {

        if (command.equals("start task") || command.equals("begin task")) {
            handleStartTask();
        }else if (command.equals("next") || command.equals("done step") || command.equals("step complete") || command.equals("step completed")) {
            handleNext();
        } else if (command.equals("back") || command.equals("go back") || command.equals("previous step")) {
            handleBack();
        }else if (command.equals("complete task")  || command.equals("completed task") || command.equals("task complete") || command.equals("task completed") || command.equals("done task") || command.equals("done with task")) {
            handleTaskComplete();
        } else if (command.startsWith("question")) {
            String question = command.substring("question".length()).trim();
            handleQuestion(question);
        } else {
            speak("Sorry, I didn't understand the command.");
        }
    }

    // Command Handlers

    private void handleStartTask() {
        mUnityPlayer.UnitySendMessage("MainController", "setCurrentTask", "0");
    }
    private void handleNext() {
        mUnityPlayer.UnitySendMessage("CAMMRADPMController", "next", "");
    }

    private void handleBack() {
        mUnityPlayer.UnitySendMessage("CAMMRADPMController", "back", "");
    }

    private void handleTaskComplete() {
        speak("Ending task");
        mUnityPlayer.UnitySendMessage("CAMMRADPMController", "finishTask", "");
    }

    private void handleQuestion(String question) {

        if (question.isEmpty()) {
            speak("Please start over and ask your question again.");
        } else {

            isProcessingUserCommand=true;

            speak("Give me a sec");

            //mUnityPlayer.UnitySendMessage("CAMMRADMainController", "hideListening", "");


            question = replaceCommonlyMisheardWords(question);

            JSONObject jsonObject = new JSONObject();
            try {
                jsonObject.put("question",question);
            } catch (JSONException e) {
                throw new RuntimeException(e);
            }

            RequestBody requestJsonBody = RequestBody.create(
                    jsonObject.toString(),
                    MediaType.parse("application/json")
            );

            Request request2 = new Request.Builder()
                    .url("https://d59d-2601-152-1402-51a0-7dec-8061-b0dc-b3f6.ngrok-free.app/jini/ask")
                    .post(requestJsonBody)
                    .build();

            client.newCall(request2).enqueue(new Callback() {
                @Override
                public void onFailure(@NotNull Call call, @NotNull IOException e) {
                    Log.d("Saboor", "Second Request failed");
                    e.printStackTrace();
                    isProcessingUserCommand=false;
                    speak("I didn't quite understand your question");
                }

                @Override
                public void onResponse(@NotNull Call call, @NotNull Response response) throws IOException {

                    String rep = response.body().string();

                    speak(rep.replaceAll("[^a-zA-Z0-9\\s]", ""));

                    //

                    Log.d("Saboor", "Normal response: " + rep);
                    Log.d("Saboor", "Altered response: " + rep.replaceAll("[^a-zA-Z0-9\\s]", ""));

                    isProcessingUserCommand=false;
                    //mUnityPlayer.UnitySendMessage("CAMMRADMainController", "showListening", "");


                }
            });

        }
    }

    public String replaceCommonlyMisheardWords(String text){

        String result = replaceAll(text, replacements);

        return result;
    }

    public static String replaceAll(String text, String[][] replacements) {
        for (String[] pair : replacements) {
            if (pair.length == 2) {
                text = text.replace(pair[0], pair[1]);
            }
        }
        return text;
    }

    public void setDoingRationale(boolean doingRationale) {
        isDoingRationale = doingRationale;
    }
}
