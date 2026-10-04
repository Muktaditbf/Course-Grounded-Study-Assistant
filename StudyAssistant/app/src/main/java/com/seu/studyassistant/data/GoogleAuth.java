package com.seu.studyassistant.data;

import android.app.Activity;
import android.content.Context;
import android.os.CancellationSignal;

import androidx.core.content.ContextCompat;
import androidx.credentials.ClearCredentialStateRequest;
import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.ClearCredentialException;
import androidx.credentials.exceptions.GetCredentialCancellationException;
import androidx.credentials.exceptions.GetCredentialException;

import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;

/**
 * "Continue with Google", through Android's Credential Manager (the current replacement for
 * the deprecated GoogleSignInClient). Produces a Google ID token, which CloudRepo exchanges for
 * a Firebase session.
 *
 * It needs the project's OAuth web client id, which the google-services plugin writes into
 * the string resource default_web_client_id - but only once Google sign-in is enabled in the
 * Firebase console and google-services.json is downloaded again. It is looked up by name at
 * runtime, so the app still builds before that, and the button explains what is missing.
 */
public final class GoogleAuth {

    private GoogleAuth() {}

    /** Thrown when the user closes the Google account picker. Not an error worth showing. */
    public static final class Cancelled extends Exception {
        Cancelled() { super("cancelled"); }
    }

    /** Thrown when Google sign-in has not been enabled for this Firebase project yet. */
    public static final class NotConfigured extends Exception {
        NotConfigured() { super("google sign-in not configured"); }
    }

    public static boolean isConfigured(Context c) {
        return webClientId(c) != null;
    }

    private static String webClientId(Context c) {
        int id = c.getResources().getIdentifier("default_web_client_id", "string", c.getPackageName());
        if (id == 0) return null;
        String v = c.getString(id);
        return v.isEmpty() ? null : v;
    }

    /** Shows the Google account sheet and returns the chosen account's ID token. */
    public static void requestIdToken(Activity activity, final Callback<String> cb) {
        String clientId = webClientId(activity);
        if (clientId == null) { cb.onError(new NotConfigured()); return; }

        GetSignInWithGoogleOption option = new GetSignInWithGoogleOption.Builder(clientId).build();
        GetCredentialRequest request = new GetCredentialRequest.Builder()
                .addCredentialOption(option)
                .build();

        CredentialManager.create(activity).getCredentialAsync(activity, request,
                new CancellationSignal(), ContextCompat.getMainExecutor(activity),
                new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                    @Override public void onResult(GetCredentialResponse response) {
                        Credential c = response.getCredential();
                        if (c instanceof CustomCredential
                                && GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(c.getType())) {
                            try {
                                cb.onSuccess(GoogleIdTokenCredential.createFrom(c.getData()).getIdToken());
                            } catch (Exception e) {
                                cb.onError(e);
                            }
                        } else {
                            cb.onError(new IllegalStateException("unexpected credential"));
                        }
                    }

                    @Override public void onError(GetCredentialException e) {
                        cb.onError(e instanceof GetCredentialCancellationException ? new Cancelled() : e);
                    }
                });
    }

    /** On sign-out: forget the chosen account, so the next sign-in can pick a different one. */
    static void forgetAccount(Context ctx) {
        try {
            CredentialManager.create(ctx).clearCredentialStateAsync(new ClearCredentialStateRequest(),
                    null, ContextCompat.getMainExecutor(ctx),
                    new CredentialManagerCallback<Void, ClearCredentialException>() {
                        @Override public void onResult(Void v) { }
                        @Override public void onError(ClearCredentialException e) { }
                    });
        } catch (Exception ignored) {
            // No credential provider on the device: nothing to clear.
        }
    }
}
