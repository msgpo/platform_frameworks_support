/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.support.v4.graphics;

import static android.support.annotation.RestrictTo.Scope.LIBRARY_GROUP;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.GuardedBy;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.RequiresApi;
import android.support.annotation.RestrictTo;
import android.support.annotation.VisibleForTesting;
import android.support.v4.content.res.FontResourcesParserCompat;
import android.support.v4.content.res.FontResourcesParserCompat.FontFamilyFilesResourceEntry;
import android.support.v4.content.res.FontResourcesParserCompat.FontFileResourceEntry;
import android.support.v4.content.res.FontResourcesParserCompat.ProviderResourceEntry;
import android.support.v4.content.res.ResourcesCompat;
import android.support.v4.graphics.TypefaceCompat.FontRequestCallback;
import android.support.v4.graphics.fonts.FontRequest;
import android.support.v4.graphics.fonts.FontResult;
import android.support.v4.os.ResultReceiver;
import android.support.v4.os.TraceCompat;
import android.support.v4.provider.FontsContractCompat;
import android.support.v4.provider.FontsContractCompat.FontInfo;
import android.support.v4.provider.FontsContractInternal;
import android.support.v4.util.LruCache;
import android.util.Log;

import java.io.Closeable;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Implementation of the Typeface compat methods for API 14 and above.
 * @hide
 */
@RestrictTo(LIBRARY_GROUP)
@RequiresApi(14)
class TypefaceCompatBaseImpl implements TypefaceCompat.TypefaceCompatImpl {
    private static final String TAG = "TypefaceCompatBaseImpl";
    private static final String CACHE_FILE_PREFIX = "cached_font_";

    private static final boolean VERBOSE_TRACING = false;
    private static final int SYNC_FETCH_TIMEOUT_MS = 500;

    /**
     * Cache for Typeface objects dynamically loaded from assets. Currently max size is 16.
     */
    private static final LruCache<String, Typeface> sDynamicTypefaceCache = new LruCache<>(16);
    private static final Object sLock = new Object();
    @GuardedBy("sLock")
    private static FontsContractInternal sFontsContract;

    private final Context mApplicationContext;

    TypefaceCompatBaseImpl(Context context) {
        mApplicationContext = context.getApplicationContext();
    }

    /**
     * Create a typeface object given a font request. The font will be asynchronously fetched,
     * therefore the result is delivered to the given callback. See {@link FontRequest}.
     * Only one of the methods in callback will be invoked, depending on whether the request
     * succeeds or fails. These calls will happen on the background thread.
     * @param request A {@link FontRequest} object that identifies the provider and query for the
     *                request. May not be null.
     * @param callback A callback that will be triggered when results are obtained. May not be null.
     */
    public void create(@NonNull final FontRequest request,
            @NonNull final FontRequestCallback callback) {
        final Typeface cachedTypeface = findFromCache(
                request.getProviderAuthority(), request.getQuery());
        if (cachedTypeface != null) {
            callback.onTypefaceRetrieved(cachedTypeface);
        }
        synchronized (sLock) {
            if (sFontsContract == null) {
                sFontsContract = new FontsContractInternal(mApplicationContext);
            }
            final ResultReceiver receiver = new ResultReceiver(null) {
                @Override
                public void onReceiveResult(final int resultCode, final Bundle resultData) {
                    receiveResult(request, callback, resultCode, resultData);
                }
            };
            sFontsContract.getFont(request, receiver);
        }
    }

    private static Typeface findFromCache(String providerAuthority, String query) {
        synchronized (sDynamicTypefaceCache) {
            final String key = createProviderUid(providerAuthority, query);
            Typeface typeface = sDynamicTypefaceCache.get(key);
            if (typeface != null) {
                return typeface;
            }
        }
        return null;
    }

    static void putInCache(String providerAuthority, String query, Typeface typeface) {
        String key = createProviderUid(providerAuthority, query);
        synchronized (sDynamicTypefaceCache) {
            sDynamicTypefaceCache.put(key, typeface);
        }
    }

    @VisibleForTesting
    void receiveResult(FontRequest request,
            FontRequestCallback callback, int resultCode, Bundle resultData) {
        Typeface cachedTypeface = findFromCache(
                request.getProviderAuthority(), request.getQuery());
        if (cachedTypeface != null) {
            // We already know the result.
            // Probably the requester requests the same font again in a short interval.
            callback.onTypefaceRetrieved(cachedTypeface);
            return;
        }
        if (resultCode != FontsContractCompat.Columns.RESULT_CODE_OK) {
            callback.onTypefaceRequestFailed(resultCode);
            return;
        }
        if (resultData == null) {
            callback.onTypefaceRequestFailed(
                    FontRequestCallback.FAIL_REASON_FONT_NOT_FOUND);
            return;
        }
        List<FontResult> resultList =
                resultData.getParcelableArrayList(FontsContractInternal.PARCEL_FONT_RESULTS);
        if (resultList == null || resultList.isEmpty()) {
            callback.onTypefaceRequestFailed(
                    FontRequestCallback.FAIL_REASON_FONT_NOT_FOUND);
            return;
        }

        Typeface typeface = createTypeface(resultList);

        if (typeface == null) {
            Log.e(TAG, "Error creating font " + request.getQuery());
            callback.onTypefaceRequestFailed(
                    FontRequestCallback.FAIL_REASON_FONT_LOAD_ERROR);
            return;
        }
        putInCache(request.getProviderAuthority(), request.getQuery(), typeface);
        callback.onTypefaceRetrieved(typeface);
    }

    /**
     * To be overriden by other implementations according to available APIs.
     * @param resultList a list of results, guaranteed to be non-null and non empty.
     */
    @Override
    public Typeface createTypeface(List<FontResult> resultList) {
        // When we load from file, we can only load one font so just take the first one.
        Typeface typeface = null;
        FileDescriptor fd = resultList.get(0).getFileDescriptor().getFileDescriptor();
        File tmpFile = copyToCacheFile(new FileInputStream(fd));
        if (tmpFile != null) {
            try {
                typeface = Typeface.createFromFile(tmpFile.getPath());
            } catch (RuntimeException e) {
                // This was thrown from Typeface.createFromFile when a Typeface could not be loaded,
                // such as due to an invalid ttf or unreadable file. We don't want to throw that
                // exception anymore.
                return null;
            } finally {
                tmpFile.delete();
            }
        }
        return typeface;
    }

    @Override
    public Typeface createTypeface(@NonNull FontInfo[] fonts, Map<Uri, ByteBuffer> uriBuffer) {
        // When we load from file, we can only load one font so just take the first one.
        if (fonts.length < 1) {
            return null;
        }
        Typeface typeface = null;
        ByteBuffer buffer = uriBuffer.get(fonts[0].getUri());
        File tmpFile = copyToCacheFile(buffer);
        if (tmpFile != null) {
            try {
                typeface = Typeface.createFromFile(tmpFile.getPath());
            } catch (RuntimeException e) {
                // This was thrown from Typeface.createFromFile when a Typeface could not be loaded,
                // such as due to an invalid ttf or unreadable file. We don't want to throw that
                // exception anymore.
                return null;
            } finally {
                tmpFile.delete();
            }
        }
        return typeface;
    }

    private File copyToCacheFile(final InputStream is) {
        FileOutputStream fos = null;
        File cacheFile;
        try {
            cacheFile = new File(mApplicationContext.getCacheDir(),
                    CACHE_FILE_PREFIX + Thread.currentThread().getId());
            fos = new FileOutputStream(cacheFile, false);

            byte[] buffer = new byte[1024];
            int readLen;
            while ((readLen = is.read(buffer)) != -1) {
                fos.write(buffer, 0, readLen);
            }
        } catch (IOException e) {
            Log.e(TAG, "Error copying font file descriptor to temp local file.", e);
            return null;
        } finally {
            closeQuietly(is);
            closeQuietly(fos);
        }
        return cacheFile;
    }

    private File copyToCacheFile(final ByteBuffer is) {
        FileOutputStream fos = null;
        File cacheFile;
        try {
            cacheFile = new File(mApplicationContext.getCacheDir(),
                    CACHE_FILE_PREFIX + Thread.currentThread().getId());
            fos = new FileOutputStream(cacheFile, false);

            byte[] buffer = new byte[1024];
            while (is.hasRemaining()) {
                int len = Math.min(1024, is.remaining());
                is.get(buffer, 0, len);
                fos.write(buffer, 0, len);
            }
        } catch (IOException e) {
            Log.e(TAG, "Error copying font file descriptor to temp local file.", e);
            return null;
        } finally {
            closeQuietly(fos);
        }
        return cacheFile;
    }

    static void closeQuietly(InputStream is) {
        if (is != null) {
            try {
                is.close();
            } catch (IOException io) {
                Log.e(TAG, "Error closing input stream", io);
            }
        }
    }

    /**
     * Creates a unique id for a given font provider and query.
     */
    private static String createProviderUid(String authority, String query) {
        return "provider:" + authority + "-" + query;
    }

    @Nullable
    @Override
    public Typeface createFromResources(Resources resources, int id, String path) {
        final String key = createAssetUid(resources, id, path);
        synchronized (sDynamicTypefaceCache) {
            Typeface typeface = sDynamicTypefaceCache.get(key);
            if (typeface != null) {
                return typeface;
            }
            try {
                typeface = createTypeface(resources, path);
                if (typeface != null) {
                    sDynamicTypefaceCache.put(key, typeface);
                }
                return typeface;
            } catch (IOException e) {
                Log.e(TAG, "Error creating font resource id " + id + " : " + path, e);
            }
        }
        Log.e(TAG, "Error creating font resource id " + id + " : " + path);
        return null;
    }

    @Nullable
    public Typeface createFromResources(FontResourcesParserCompat.FamilyResourceEntry entry,
            Resources resources, int id, String path) {
        Typeface typeface = findFromCache(resources, id, path);
        if (typeface != null) return typeface;

        if (entry instanceof ProviderResourceEntry) {
            final ProviderResourceEntry providerEntry = (ProviderResourceEntry) entry;
            return createFromResources(providerEntry);
        }

        // family is FontFamilyFilesResourceEntry
        final FontFamilyFilesResourceEntry filesEntry =
                (FontFamilyFilesResourceEntry) entry;
        typeface = createFromResources(filesEntry, resources, id, path);
        if (typeface != null) {
            final String key = createAssetUid(resources, id, path);
            sDynamicTypefaceCache.put(key, typeface);
        }
        return typeface;
    }

    /**
     * Implementation of resources font retrieval for a file type xml resource. This should be
     * overriden by other implementations.
     */
    @Nullable
    Typeface createFromResources(FontFamilyFilesResourceEntry entry, Resources resources, int id,
            String path) {
        // When creating from file, we only support one font at a time.
        FontFileResourceEntry[] entries = entry.getEntries();
        FontFileResourceEntry firstEntry = entries[0];
        return ResourcesCompat.getFont(mApplicationContext, firstEntry.getResourceId());
    }

    @Nullable
    private Typeface createFromResources(ProviderResourceEntry entry) {
        Typeface typeface = findFromCache(entry.getAuthority(), entry.getQuery());
        if (typeface != null) {
            return typeface;
        }
        FontRequest request = new FontRequest(entry.getAuthority(), entry.getPackage(),
                entry.getQuery(), entry.getCerts());
        WaitableCallback callback =
                new WaitableCallback(entry.getAuthority() + "/" + entry.getQuery());
        create(request, callback);
        return callback.waitWithTimeout(SYNC_FETCH_TIMEOUT_MS);
    }

    private static final class WaitableCallback extends FontRequestCallback {
        private final ReentrantLock mLock = new ReentrantLock();
        private final Condition mCond = mLock.newCondition();

        private final String mFontTitle;  // For debugging message.

        private static final int NOT_STARTED = 0;
        private static final int WAITING = 1;
        private static final int FINISHED = 2;
        @GuardedBy("mLock")
        private int mState = NOT_STARTED;

        @GuardedBy("mLock")
        private Typeface mTypeface;

        WaitableCallback(String fontTitle) {
            mFontTitle = fontTitle;
        }

        @Override
        public void onTypefaceRetrieved(Typeface typeface) {
            mLock.lock();
            try {
                if (mState == WAITING) {
                    mTypeface = typeface;
                    mState = FINISHED;
                }
                mCond.signal();
            } finally {
                mLock.unlock();
            }
        }

        @Override
        public void onTypefaceRequestFailed(@FontRequestFailReason int reason) {
            Log.w(TAG, "Remote font fetch failed(" + reason + "): " + mFontTitle);
            mLock.lock();
            try {
                if (mState == WAITING) {
                    mTypeface = null;
                    mState = FINISHED;
                }
                mCond.signal();
            } finally {
                mLock.unlock();
            }
        }

        public Typeface waitWithTimeout(long timeoutMillis) {
            if (VERBOSE_TRACING) {
                TraceCompat.beginSection("Remote Font Fetch");
            }
            mLock.lock();
            try {
                if (mState == FINISHED) {
                    return mTypeface;  // Already has a result.
                }
                if (mState != NOT_STARTED) {
                    return null;  // Unexpected state. Reusing the same callback again?
                }
                mState = WAITING;
                long remainingNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
                while (mState == WAITING) {
                    try {
                        remainingNanos = mCond.awaitNanos(remainingNanos);
                    } catch (InterruptedException e) {
                    }
                    if (mState == FINISHED) {
                        final long elapsedMillis =
                                timeoutMillis - TimeUnit.NANOSECONDS.toMillis(remainingNanos);
                        Log.w(TAG, "Remote font fetched in " + elapsedMillis + "ms :" + mFontTitle);
                        return mTypeface;
                    }
                    if (remainingNanos < 0) {
                        Log.w(TAG, "Remote font fetch timed out: " + mFontTitle);
                        mState = FINISHED;
                        return null;  // Timed out.
                    }
                }
                return null;
            } finally {
                mLock.unlock();
                if (VERBOSE_TRACING) {
                    TraceCompat.endSection();
                }
            }
        }
    };

    @Override
    public Typeface findFromCache(Resources resources, int id, String path) {
        final String key = createAssetUid(resources, id, path);
        synchronized (sDynamicTypefaceCache) {
            Typeface typeface = sDynamicTypefaceCache.get(key);
            if (typeface != null) {
                return typeface;
            }
        }
        return null;
    }

    /**
     * Creates a unique id for a given AssetManager and asset path.
     *
     * @param resources Resources instance
     * @param path The path for the asset.
     * @return Unique id for a given AssetManager and asset path.
     */
    private static String createAssetUid(final Resources resources, int id, String path) {
        return resources.getResourcePackageName(id) + "-" + path;
    }

    Typeface createTypeface(Resources resources, String path) throws IOException {
        Typeface typeface = null;
        AssetFileDescriptor fd = resources.getAssets().openNonAssetFd(path);
        File tmpFile = copyToCacheFile(fd.createInputStream());
        if (tmpFile != null) {
            try {
                typeface = Typeface.createFromFile(tmpFile.getPath());
            } catch (RuntimeException e) {
                // This was thrown from Typeface.createFromFile when a Typeface could not be loaded,
                // such as due to an invalid ttf or unreadable file. We don't want to throw that
                // exception anymore.
                android.util.Log.e(TAG, "Failed to create font", e);
                return null;
            } finally {
                tmpFile.delete();
            }
        }
        return typeface;
    }

    static void closeQuietly(Closeable stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException io) {
                Log.e(TAG, "Error closing stream", io);
            }
        }
    }
}
