package com.zapic.sdk.android;

import android.os.AsyncTask;
import android.os.Build;
import android.support.annotation.MainThread;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.WorkerThread;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Random;

final class AppSourceAsyncTask extends AsyncTask<Void, Integer, AppSource> implements CancellationToken {
    /**
     * The cache file name.
     */
    @NonNull
    private static final String APP_SOURCE_FILE_NAME = "zapic.index.html.cacheEntry";

    /**
     * The tag used to identify log entries.
     */
    @NonNull
    private static final String TAG = "AppSourceAsyncTask";

    /**
     * The web client application URL.
     */
    @NonNull
    private final URL mAppUrl;

    /**
     * The Android application's cache directory.
     */
    @NonNull
    private final File mCacheDir;

    /**
     * Creates a new instance.
     *
     * @param url      The web client application URL.
     * @param cacheDir The Android application's cache directory.
     * @throws IllegalArgumentException If {@code url} is invalid.
     */
    AppSourceAsyncTask(@NonNull final String url, @NonNull final File cacheDir) {
        URL parsedUrl;
        try {
            parsedUrl = new URL(url);
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("The web client application URL is invalid");
        }

        this.mAppUrl = parsedUrl;
        this.mCacheDir = cacheDir;
    }

    @Nullable
    @Override
    @WorkerThread
    protected AppSource doInBackground(final Void... voids) {
        final AppSource cachedAppSource = AppSourceConfig.isCacheEnabled() ? this.getFromCache() : null;
        if (cachedAppSource != null) {
            final long lastValidated = cachedAppSource.getLastValidated();
            final long now = System.currentTimeMillis();
            long staleness;
            if (lastValidated <= 0) {
                final long lastModified = cachedAppSource.getLastModified();
                staleness = now - lastModified;
            } else {
                staleness = now - lastValidated;
            }

            if (staleness < 86400000) {
                return this.injectInitializationScript(cachedAppSource);
            }
        }

        if (this.isCancelled()) {
            return null;
        }

        final AppSource downloadedAppSource = this.download();
        if (downloadedAppSource == null) {
            return null;
        }

        if (this.isCancelled()) {
            return null;
        }

        this.putInCache(downloadedAppSource);
        return this.injectInitializationScript(downloadedAppSource);
    }

    /**
     * Downloads the web client application.
     *
     * @return The web client application source and version or {@code null} if the task was
     * cancelled.
     */
    @Nullable
    @WorkerThread
    private AppSource download() {
        final Random random = new Random();
        int failures = 0;
        while (true) {
            HttpURLConnection connection = null;
            BufferedReader reader = null;
            try {
                // Connect to web server.
                Log.d(TAG, String.format("Downloading web client application from %s", this.mAppUrl));
                connection = (HttpURLConnection) this.mAppUrl.openConnection();
                connection.setAllowUserInteraction(false);
                connection.setConnectTimeout(30000);
                connection.setInstanceFollowRedirects(false);
                connection.setReadTimeout(30000);
                connection.setUseCaches(false);
                connection.connect();
                if (this.isCancelled()) {
                    return null;
                }

                // Parse response headers.
                final int statusCode = connection.getResponseCode();
                if (statusCode == HttpURLConnection.HTTP_OK) {
                    final long lastModified = connection.getLastModified();
                    String eTag = connection.getHeaderField("ETag");
                    if (eTag == null) {
                        eTag = "";
                    } else {
                        eTag = eTag.trim();
                    }

                    // Parse response body.
                    final StringBuilder htmlBuilder = new StringBuilder();
                    reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                    final char[] buffer = new char[1024 * 4];
                    int n;
                    while ((n = reader.read(buffer)) != -1) {
                        if (this.isCancelled()) {
                            return null;
                        }

                        htmlBuilder.append(buffer, 0, n);
                    }

                    final String html = htmlBuilder.toString();

                    Log.d(TAG, "Downloaded web client application");
                    Log.d(TAG, html);
                    return new AppSource(html, eTag, lastModified, System.currentTimeMillis());
                }

                Log.e(TAG, String.format("Failed to download web client application with HTTP status code %d", statusCode));
            } catch (IOException e) {
                Log.e(TAG, "Failed to download web client application", e);
            } finally {
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (IOException e) {
                        Log.e(TAG, "Failed to close input stream", e);
                    }
                }

                if (connection != null) {
                    connection.disconnect();
                }
            }

            if (this.isCancelled()) {
                return null;
            }

            // Ensure we don't overflow.
            if (Integer.MAX_VALUE != failures) {
                Log.d(TAG, String.format("Failed %d time(s) to download web client application", ++failures));
                this.publishProgress(failures);
            }

            try {
                // Ensure we don't overflow; limit the maximum delay to ~30 minutes.
                final int clampedRetries = (failures > 14 ? 14 : failures) - 1;
                final int maximumDelay = (int) Math.pow(2, clampedRetries) * 100;

                // Limit the minimum delay to 100 milliseconds.
                final int delay = random.nextInt(maximumDelay) + 100;
                Log.d(TAG, String.format("Retry to download web client application in %d milliseconds", delay));

                Thread.sleep(delay);
            } catch (InterruptedException ignored) {
            }

            if (this.isCancelled()) {
                return null;
            }
        }
    }

    /**
     * Gets the web client application source and version from the cache.
     *
     * @return The web client application source and version or {@code null} if the task was
     * cancelled or if the web client application source and version has not been cached.
     */
    @Nullable
    @WorkerThread
    private AppSource getFromCache() {
        String contents;
        try {
            contents = CacheFileUtilities.readFile(this.mCacheDir, APP_SOURCE_FILE_NAME, this);
            if (this.isCancelled()) {
                return null;
            }

            if (contents == null) {
                try {
                    CacheFileUtilities.deleteFile(this.mCacheDir, APP_SOURCE_FILE_NAME, this);
                } catch (IOException ignored) {
                }

                return null;
            }
        } catch (IOException e) {
            Log.i(TAG, "Failed to read web client application source and version from cache");
            return null;
        }

        try {
            final JSONObject json = new JSONObject(contents);
            return new AppSource(json.getString("HTML"), json.getString("ETag"), json.getLong("LastModified"), json.optLong("LastValidated", 0));
        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse web client application source and version from cache", e);

            try {
                CacheFileUtilities.deleteFile(this.mCacheDir, APP_SOURCE_FILE_NAME, this);
            } catch (IOException ignored) {
            }

            return null;
        }
    }

    /**
     * Injects the initialization script into the web client application HTML.
     *
     * @param appSource The <i>original</i> web client application source and version.
     * @return The modified web client application source and version.
     */
    @NonNull
    @WorkerThread
    private AppSource injectInitializationScript(@NonNull final AppSource appSource) {
        final String html = appSource.getHtml();
        final int startOfHead = html.indexOf("<head>");
        if (startOfHead == -1) {
            return appSource;
        }

        final int endOfHead = startOfHead + "<head>".length();
        final String script = "<script>" +
                "window.zapic = {" +
                "  environment: 'webview'," +
                "  version: 2," +
                "  androidVersion: '" + String.valueOf(Build.VERSION.SDK_INT).replace("'", "\\'") + "'," +
                "  onLoaded: function (action$, publishAction) {" +
                "    window.zapic.dispatch = function (action) {" +
                "      publishAction(action)" +
                "    };" +
                "    action$.subscribe(function (action) {" +
                "      window.androidWebView.dispatch(JSON.stringify(action))" +
                "    });" +
                "  }" +
                "};" +
                "</script>".replaceAll(" +", "");

        final StringBuilder htmlBuilder = new StringBuilder(html);
        htmlBuilder.insert(endOfHead, script);

        return new AppSource(htmlBuilder.toString(), appSource.getETag(), appSource.getLastModified(), appSource.getLastValidated());
    }

    @MainThread
    @Override
    protected void onPostExecute(@Nullable AppSource appSource) {
        if (appSource == null) {
            WebViewManager.getInstance().cancelLoadApp();
        } else {
            WebViewManager.getInstance().submitLoadApp(appSource);
        }
    }

    @MainThread
    @Override
    protected void onProgressUpdate(final Integer... values) {
        // TODO: Bail early if it makes sense.
        // this.cancel(true);
    }

    /**
     * Puts the web client application source and version in the cache.
     *
     * @param appSource The web client application source and version.
     */
    @WorkerThread
    private void putInCache(@NonNull final AppSource appSource) {
        final JSONObject json = new JSONObject();
        try {
            json.put("HTML", appSource.getHtml());
            json.put("ETag", appSource.getETag());
            json.put("LastModified", appSource.getLastModified());
            if (appSource.getLastValidated() > 0) {
                json.put("LastValidated", appSource.getLastValidated());
            }
        } catch (JSONException ignored) {
            // It is not clear from the JSONObject documentation why this would ever occur.
        }

        try {
            CacheFileUtilities.writeFile(this.mCacheDir, APP_SOURCE_FILE_NAME, json.toString(), this);
        } catch (IOException e) {
            Log.i(TAG, "Failed to write web client application source and version to cache");
        }
    }
}
