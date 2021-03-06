/*
 * Copyright (C) 2009 The Android Open Source Project
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
 * limitations under the License
 */

package novoda.lib.sqliteprovider.provider;

import android.content.*;
import android.database.sqlite.*;
import android.net.Uri;

import java.util.ArrayList;

/**
 * General purpose {@link android.content.ContentProvider} base class that uses SQLiteDatabase
 * for storage.
 */
public abstract class SQLiteContentProvider extends ContentProvider implements SQLiteTransactionListener {

    private static final int SLEEP_AFTER_YIELD_DELAY = 4000;

    private final ThreadLocal<Boolean> mApplyingBatch = new ThreadLocal<Boolean>();
    private SQLiteOpenHelper mOpenHelper;

    private volatile boolean mNotifyChange;

    @Override
    public boolean onCreate() {
        mOpenHelper = getDatabaseHelper(getContext());
        return true;
    }

    protected abstract SQLiteOpenHelper getDatabaseHelper(Context context);

    /**
     * The equivalent of the {@link #insert} method, but invoked within a
     * transaction.
     */
    protected abstract Uri insertInTransaction(Uri uri, ContentValues values);

    /**
     * The equivalent of the {@link #update} method, but invoked within a
     * transaction.
     */
    protected abstract int updateInTransaction(Uri uri, ContentValues values, String selection,
            String[] selectionArgs);

    /**
     * The equivalent of the {@link #delete} method, but invoked within a
     * transaction.
     */
    protected abstract int deleteInTransaction(Uri uri, String selection, String[] selectionArgs);

    protected abstract void notifyChange();

    protected SQLiteOpenHelper getDatabaseHelper() {
        return mOpenHelper;
    }

    private boolean applyingBatch() {
        return mApplyingBatch.get() != null && mApplyingBatch.get();
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        Uri result = null;
        boolean applyingBatch = applyingBatch();
        if (!applyingBatch) {
            SQLiteDatabase mDb = mOpenHelper.getWritableDatabase();
            mDb.beginTransactionWithListener(this);
            try {
                result = insertInTransaction(uri, values);
                if (result != null) {
                    mNotifyChange = true;
                }
                mDb.setTransactionSuccessful();
            } finally {
                mDb.endTransaction();
            }

            onEndTransaction();
        } else {
            result = insertInTransaction(uri, values);
            if (result != null) {
                mNotifyChange = true;
            }
        }
        return result;
    }

    @Override
    public int bulkInsert(Uri uri, ContentValues[] values) {
        int numValues = values.length;
        SQLiteDatabase mDb = mOpenHelper.getWritableDatabase();
        mDb.beginTransactionWithListener(this);
        try {
            for (int i = 0; i < numValues; i++) {
                Uri result = insertInTransaction(uri, values[i]);
                if (result != null) {
                    mNotifyChange = true;
                }
                mDb.yieldIfContendedSafely();
            }
            mDb.setTransactionSuccessful();
        } finally {
            mDb.endTransaction();
        }

        onEndTransaction();
        return numValues;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        int count = 0;
        boolean applyingBatch = applyingBatch();
        if (!applyingBatch) {
            SQLiteDatabase mDb = mOpenHelper.getWritableDatabase();
            mDb.beginTransactionWithListener(this);
            try {
                count = updateInTransaction(uri, values, selection, selectionArgs);
                if (count > 0) {
                    mNotifyChange = true;
                }
                mDb.setTransactionSuccessful();
            } finally {
                mDb.endTransaction();
            }

            onEndTransaction();
        } else {
            count = updateInTransaction(uri, values, selection, selectionArgs);
            if (count > 0) {
                mNotifyChange = true;
            }
        }

        return count;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        int count = 0;
        boolean applyingBatch = applyingBatch();
        if (!applyingBatch) {
            SQLiteDatabase mDb = mOpenHelper.getWritableDatabase();
            mDb.beginTransactionWithListener(this);
            try {
                count = deleteInTransaction(uri, selection, selectionArgs);
                if (count > 0) {
                    mNotifyChange = true;
                }
                mDb.setTransactionSuccessful();
            } finally {
                mDb.endTransaction();
            }

            onEndTransaction();
        } else {
            count = deleteInTransaction(uri, selection, selectionArgs);
            if (count > 0) {
                mNotifyChange = true;
            }
        }
        return count;
    }

    @Override
    public ContentProviderResult[] applyBatch(ArrayList<ContentProviderOperation> operations)
            throws OperationApplicationException {
        SQLiteDatabase mDb = mOpenHelper.getWritableDatabase();
        mDb.beginTransactionWithListener(this);
        try {
            mApplyingBatch.set(true);
            final int numOperations = operations.size();
            final ContentProviderResult[] results = new ContentProviderResult[numOperations];
            for (int i = 0; i < numOperations; i++) {
                final ContentProviderOperation operation = operations.get(i);
                if (i > 0 && operation.isYieldAllowed()) {
                    mDb.yieldIfContendedSafely(SLEEP_AFTER_YIELD_DELAY);
                }
                results[i] = operation.apply(this, results, i);
            }
            mDb.setTransactionSuccessful();
            return results;
        } finally {
            mApplyingBatch.set(false);
            mDb.endTransaction();
            onEndTransaction();
        }
    }

    @Override
    public void onBegin() {
        onBeginTransaction();
    }

    @Override
    public void onCommit() {
        beforeTransactionCommit();
    }

    @Override
    public void onRollback() {
        // not used
    }

    protected void onBeginTransaction() {
    }

    protected void beforeTransactionCommit() {
    }

    protected void onEndTransaction() {
        if (mNotifyChange) {
            mNotifyChange = false;
            notifyChange();
        }
    }
}
