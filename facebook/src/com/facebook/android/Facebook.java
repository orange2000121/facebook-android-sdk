/*
 * Copyright 2010 Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.android;

import java.util.LinkedList;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONStringer;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;

import com.facebook.android.Util.Callback;

// TODO(ssoneff):
// callback handler?
// error codes?
// javadocs
// tidy up eclipse config...
// copyrights

// size, title of uiActivity
// support multiple facebook sessions?  provide session manager?
// wrapper for data: FacebookObject?
// lower pri: auto uiInteraction on session failure?
// request queue?  request callbacks for loading, cancelled?

// Questions:
// fix fbconnect://...
// oauth redirect not working
// oauth does not return expires_in
// expires_in is duration or expiration?
// for errors: use string (message), error codes, or exceptions?
// why callback on both receive response and loaded?
// keep track of which permissions this session has?

public class Facebook {

    public static final String REDIRECT_URI = "fbconnect://success";
    public static final String TOKEN = "access_token";
    public static final String EXPIRES = "expires_in";
    
    protected static String OAUTH_ENDPOINT =
        "http://graph.facebook.com/oauth/authorize";
    protected static String UI_SERVER = 
        "http://www.facebook.com/connect/uiserver.php";
    protected static String GRAPH_BASE_URL = 
        "https://graph.facebook.com/";
    protected static String RESTSERVER_URL = 
        "https://api.facebook.com/restserver.php";
    
    private String mAccessToken = null;
    private long mAccessExpires = 0;
    private LinkedList<LogoutListener> mLogoutListeners =
        new LinkedList<LogoutListener>();


    // Initialization
    public void authorize(Context context,
                          String applicationId,
                          String[] permissions,
                          final DialogListener listener) {
        Bundle params = new Bundle();
        //TODO(brent) fix login page post parameters for display=touch
        //params.putString("display", "touch");
        params.putString("type", "user_agent");
        params.putString("client_id", applicationId);
        params.putString("redirect_uri", REDIRECT_URI);
        params.putString("scope", Util.join(permissions, ","));
        dialog(context, "login", params, new DialogListener() {

            @Override
            public void onDialogSucceed(Bundle values) {
                setAccessToken(values.getString(TOKEN));
                setAccessExpiresIn(values.getString(EXPIRES));
                Log.d("Facebook-authorize", "Login Succeeded! access_token=" +
                        getAccessToken() + " expires=" + getAccessExpires());
                listener.onDialogSucceed(values);
            }

            @Override
            public void onDialogFail(String error) {
                Log.d("Facebook-authorize", "Login failed: " + error);
                listener.onDialogFail(error);
            }

            @Override
            public void onDialogCancel() {
                Log.d("Facebook-authorize", "Login cancelled");
                listener.onDialogCancel();
            }
        });
    }
    
    public void addLogoutListener(LogoutListener l) {
        mLogoutListeners.add(l);
    }
    
    public void removeLogoutListener(LogoutListener l) {
        mLogoutListeners.remove(l);
    }
    
    public void logout(Context context) {
        for (LogoutListener l : mLogoutListeners) {
            l.onLogoutBegin();
        }
        @SuppressWarnings("unused")  // Prevent illegal state exception
        CookieSyncManager cookieSyncMngr = 
            CookieSyncManager.createInstance(context);
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.removeAllCookie();
        Bundle b = new Bundle();
        b.putString("method", "auth.expireSession");
        request(b, new RequestListener() {
            
            @Override
            public void onRequestSucceed(JSONObject response) {
                setAccessToken(null);
                setAccessExpires(0);
                for (LogoutListener l : mLogoutListeners) {
                    l.onLogoutFinish();
                }
            }
            
            @Override
            public void onRequestFail(String error) {
                Log.w("Facebook-SDK", "auth.expireSession request failed, " +
                        "but local session state cleared");
                onRequestSucceed(null);
            }
        });
    }
    
    // API requests

    // support old API: method provided as parameter
    public void request(Bundle parameters,
                        RequestListener listener) {
        request(null, "GET", parameters, listener);
    }

    public void request(String graphPath,
                        RequestListener listener) {
        request(graphPath, "GET", new Bundle(), listener);
    }

    public void request(String graphPath,
                        Bundle parameters,
                        RequestListener listener) {
        request(graphPath, "GET", parameters, listener);
    }

    public void request(String graphPath,
                        String httpMethod,
                        Bundle parameters,
                        final RequestListener listener) {
        if (isSessionValid()) {
            parameters.putString(TOKEN, getAccessToken());
        }
        parameters.putString("format", "json");
        String url = graphPath != null ?
                GRAPH_BASE_URL + graphPath : 
                RESTSERVER_URL;
        Util.asyncOpenUrl(url, httpMethod, parameters, new Callback() {
            public void call(String response) {
                Log.d("Facebook-SDK", "Got response: " + response);
                
                // Edge case: when sending a POST request to /[post_id]/likes
                // the return value is 'true' or 'false'. Unfortunately
                // these values cause the JSONObject constructor to throw
                // an exception.
                if (response.equals("true")) {
                	listener.onRequestSucceed(null);
                	return;
                }
                if (response.equals("false")) {
                	listener.onRequestFail(null);
                	return;
                }
                
                try {
                    JSONObject o = new JSONObject(response);
                    if (o.has("error")) {
                        listener.onRequestFail(o.getString("error"));
                    } else {
                        listener.onRequestSucceed(o);
                    }
                } catch (JSONException e) {
                    listener.onRequestFail(e.getMessage());
                }
            }
        });
    }


    // UI Server requests

    public void dialog(Context context,
                       String action,
                       DialogListener listener) {
        dialog(context, action, null, listener);
    }

    public void dialog(Context context,
                       String action, 
                       Bundle parameters,
                       final DialogListener listener) {
        // need logic to determine correct endpoint for resource
        // e.g. "login" --> "oauth/authorize"
        String endpoint = action.equals("login") ? OAUTH_ENDPOINT : UI_SERVER;
        String url = endpoint + "?" + Util.encodeUrl(parameters);
        new FbDialog(context, url, listener).show();
    }

    
    // Utilities

    public boolean isSessionValid() {
        return (getAccessToken() != null) && ((getAccessExpires() == 0) || 
            (System.currentTimeMillis() < getAccessExpires()));
    }

    /**
     * Retrieve the OAuth 2.0 access token for API access: treat with care.
     * Returns null if no session exists.
     * 
     * Note that this method accesses global state and is synchronized for
     * thread safety.
     * 
     * @return access token
     */
    public String getAccessToken() {
        return mAccessToken;
    }

    /**
     * Retrieve the current session's expiration time (in milliseconds since
     * Unix epoch), or 0 is no session exists.
     * 
     * Note that this method accesses global state and is synchronized for
     * thread safety.
     * 
     * @return session expiration time
     */
    public long getAccessExpires() {
        return mAccessExpires;
    }
    
    public void setAccessToken(String token) {
        mAccessToken = token;
    }

    public void setAccessExpires(long time) {
        mAccessExpires = time;
    }
    
    public void setAccessExpiresIn(String expires_in) {
        if (expires_in != null) {
            setAccessExpires(System.currentTimeMillis() + 
                    Integer.parseInt(expires_in) * 1000);
        }
    }
    

    // Callback Interfaces

    // Questions:
    // problem: callbacks are called in background thread, not UI thread: changes to UI need to be done in UI thread
    // solution 0: make all the interfaces blocking -- but lots of work for developers to get working!
    // solution 1: let the SDK users handle this -- they write code to post action back to UI thread (current)
    // solution 2: add extra callback methods -- one for background thread to call, on for UI thread (perhaps simplest?)
    // solution 3: let developer explicitly provide handler to run the callback
    // solution 4: run everything in the UI thread
    
    public static abstract class LogoutListener {

        public void onLogoutBegin() { }

        public void onLogoutFinish() { }
    }
    
    public static abstract class RequestListener {

        public abstract void onRequestSucceed(JSONObject response);

        public abstract void onRequestFail(String error);
    }

    public static abstract class DialogListener {

        public abstract void onDialogSucceed(Bundle values);

        public abstract void onDialogFail(String error);

        public void onDialogCancel() { }
    }

}