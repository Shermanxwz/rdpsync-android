package com.rdp.sync.data;

import android.database.Cursor;
import android.os.CancellationSignal;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.CoroutinesRoom;
import androidx.room.EntityDeletionOrUpdateAdapter;
import androidx.room.EntityInsertionAdapter;
import androidx.room.RoomDatabase;
import androidx.room.RoomSQLiteQuery;
import androidx.room.SharedSQLiteStatement;
import androidx.room.util.CursorUtil;
import androidx.room.util.DBUtil;
import androidx.sqlite.db.SupportSQLiteStatement;
import java.lang.Class;
import java.lang.Exception;
import java.lang.Integer;
import java.lang.Long;
import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.lang.SuppressWarnings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import javax.annotation.processing.Generated;
import kotlin.coroutines.Continuation;
import kotlinx.coroutines.flow.Flow;

@Generated("androidx.room.RoomProcessor")
@SuppressWarnings({"unchecked", "deprecation"})
public final class DeviceDao_Impl implements DeviceDao {
  private final RoomDatabase __db;

  private final EntityInsertionAdapter<Device> __insertionAdapterOfDevice;

  private final EntityDeletionOrUpdateAdapter<Device> __deletionAdapterOfDevice;

  private final EntityDeletionOrUpdateAdapter<Device> __updateAdapterOfDevice;

  private final SharedSQLiteStatement __preparedStmtOfDeleteAllDevices;

  public DeviceDao_Impl(@NonNull final RoomDatabase __db) {
    this.__db = __db;
    this.__insertionAdapterOfDevice = new EntityInsertionAdapter<Device>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT OR REPLACE INTO `devices` (`id`,`name`,`host`,`port`,`username`,`password`,`domain`,`width`,`height`) VALUES (nullif(?, 0),?,?,?,?,?,?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final Device entity) {
        statement.bindLong(1, entity.getId());
        statement.bindString(2, entity.getName());
        statement.bindString(3, entity.getHost());
        statement.bindLong(4, entity.getPort());
        statement.bindString(5, entity.getUsername());
        statement.bindString(6, entity.getPassword());
        statement.bindString(7, entity.getDomain());
        statement.bindLong(8, entity.getWidth());
        statement.bindLong(9, entity.getHeight());
      }
    };
    this.__deletionAdapterOfDevice = new EntityDeletionOrUpdateAdapter<Device>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "DELETE FROM `devices` WHERE `id` = ?";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final Device entity) {
        statement.bindLong(1, entity.getId());
      }
    };
    this.__updateAdapterOfDevice = new EntityDeletionOrUpdateAdapter<Device>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "UPDATE OR ABORT `devices` SET `id` = ?,`name` = ?,`host` = ?,`port` = ?,`username` = ?,`password` = ?,`domain` = ?,`width` = ?,`height` = ? WHERE `id` = ?";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final Device entity) {
        statement.bindLong(1, entity.getId());
        statement.bindString(2, entity.getName());
        statement.bindString(3, entity.getHost());
        statement.bindLong(4, entity.getPort());
        statement.bindString(5, entity.getUsername());
        statement.bindString(6, entity.getPassword());
        statement.bindString(7, entity.getDomain());
        statement.bindLong(8, entity.getWidth());
        statement.bindLong(9, entity.getHeight());
        statement.bindLong(10, entity.getId());
      }
    };
    this.__preparedStmtOfDeleteAllDevices = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM devices";
        return _query;
      }
    };
  }

  @Override
  public Object insertDevice(final Device device, final Continuation<? super Long> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Long>() {
      @Override
      @NonNull
      public Long call() throws Exception {
        __db.beginTransaction();
        try {
          final Long _result = __insertionAdapterOfDevice.insertAndReturnId(device);
          __db.setTransactionSuccessful();
          return _result;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object insertDevices(final List<Device> devices,
      final Continuation<? super List<Long>> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<List<Long>>() {
      @Override
      @NonNull
      public List<Long> call() throws Exception {
        __db.beginTransaction();
        try {
          final List<Long> _result = __insertionAdapterOfDevice.insertAndReturnIdsList(devices);
          __db.setTransactionSuccessful();
          return _result;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object deleteDevice(final Device device, final Continuation<? super Integer> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Integer>() {
      @Override
      @NonNull
      public Integer call() throws Exception {
        int _total = 0;
        __db.beginTransaction();
        try {
          _total += __deletionAdapterOfDevice.handle(device);
          __db.setTransactionSuccessful();
          return _total;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object updateDevice(final Device device, final Continuation<? super Integer> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Integer>() {
      @Override
      @NonNull
      public Integer call() throws Exception {
        int _total = 0;
        __db.beginTransaction();
        try {
          _total += __updateAdapterOfDevice.handle(device);
          __db.setTransactionSuccessful();
          return _total;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object deleteAllDevices(final Continuation<? super Integer> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Integer>() {
      @Override
      @NonNull
      public Integer call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfDeleteAllDevices.acquire();
        try {
          __db.beginTransaction();
          try {
            final Integer _result = _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return _result;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfDeleteAllDevices.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Flow<List<Device>> getAllDevices() {
    final String _sql = "SELECT * FROM devices ORDER BY name ASC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"devices"}, new Callable<List<Device>>() {
      @Override
      @NonNull
      public List<Device> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfName = CursorUtil.getColumnIndexOrThrow(_cursor, "name");
          final int _cursorIndexOfHost = CursorUtil.getColumnIndexOrThrow(_cursor, "host");
          final int _cursorIndexOfPort = CursorUtil.getColumnIndexOrThrow(_cursor, "port");
          final int _cursorIndexOfUsername = CursorUtil.getColumnIndexOrThrow(_cursor, "username");
          final int _cursorIndexOfPassword = CursorUtil.getColumnIndexOrThrow(_cursor, "password");
          final int _cursorIndexOfDomain = CursorUtil.getColumnIndexOrThrow(_cursor, "domain");
          final int _cursorIndexOfWidth = CursorUtil.getColumnIndexOrThrow(_cursor, "width");
          final int _cursorIndexOfHeight = CursorUtil.getColumnIndexOrThrow(_cursor, "height");
          final List<Device> _result = new ArrayList<Device>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final Device _item;
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final String _tmpName;
            _tmpName = _cursor.getString(_cursorIndexOfName);
            final String _tmpHost;
            _tmpHost = _cursor.getString(_cursorIndexOfHost);
            final int _tmpPort;
            _tmpPort = _cursor.getInt(_cursorIndexOfPort);
            final String _tmpUsername;
            _tmpUsername = _cursor.getString(_cursorIndexOfUsername);
            final String _tmpPassword;
            _tmpPassword = _cursor.getString(_cursorIndexOfPassword);
            final String _tmpDomain;
            _tmpDomain = _cursor.getString(_cursorIndexOfDomain);
            final int _tmpWidth;
            _tmpWidth = _cursor.getInt(_cursorIndexOfWidth);
            final int _tmpHeight;
            _tmpHeight = _cursor.getInt(_cursorIndexOfHeight);
            _item = new Device(_tmpId,_tmpName,_tmpHost,_tmpPort,_tmpUsername,_tmpPassword,_tmpDomain,_tmpWidth,_tmpHeight);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
        }
      }

      @Override
      protected void finalize() {
        _statement.release();
      }
    });
  }

  @Override
  public Object getDeviceById(final long deviceId, final Continuation<? super Device> $completion) {
    final String _sql = "SELECT * FROM devices WHERE id = ?";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindLong(_argIndex, deviceId);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<Device>() {
      @Override
      @Nullable
      public Device call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfName = CursorUtil.getColumnIndexOrThrow(_cursor, "name");
          final int _cursorIndexOfHost = CursorUtil.getColumnIndexOrThrow(_cursor, "host");
          final int _cursorIndexOfPort = CursorUtil.getColumnIndexOrThrow(_cursor, "port");
          final int _cursorIndexOfUsername = CursorUtil.getColumnIndexOrThrow(_cursor, "username");
          final int _cursorIndexOfPassword = CursorUtil.getColumnIndexOrThrow(_cursor, "password");
          final int _cursorIndexOfDomain = CursorUtil.getColumnIndexOrThrow(_cursor, "domain");
          final int _cursorIndexOfWidth = CursorUtil.getColumnIndexOrThrow(_cursor, "width");
          final int _cursorIndexOfHeight = CursorUtil.getColumnIndexOrThrow(_cursor, "height");
          final Device _result;
          if (_cursor.moveToFirst()) {
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final String _tmpName;
            _tmpName = _cursor.getString(_cursorIndexOfName);
            final String _tmpHost;
            _tmpHost = _cursor.getString(_cursorIndexOfHost);
            final int _tmpPort;
            _tmpPort = _cursor.getInt(_cursorIndexOfPort);
            final String _tmpUsername;
            _tmpUsername = _cursor.getString(_cursorIndexOfUsername);
            final String _tmpPassword;
            _tmpPassword = _cursor.getString(_cursorIndexOfPassword);
            final String _tmpDomain;
            _tmpDomain = _cursor.getString(_cursorIndexOfDomain);
            final int _tmpWidth;
            _tmpWidth = _cursor.getInt(_cursorIndexOfWidth);
            final int _tmpHeight;
            _tmpHeight = _cursor.getInt(_cursorIndexOfHeight);
            _result = new Device(_tmpId,_tmpName,_tmpHost,_tmpPort,_tmpUsername,_tmpPassword,_tmpDomain,_tmpWidth,_tmpHeight);
          } else {
            _result = null;
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @Override
  public Object getDeviceByName(final String name, final Continuation<? super Device> $completion) {
    final String _sql = "SELECT * FROM devices WHERE name = ? LIMIT 1";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindString(_argIndex, name);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<Device>() {
      @Override
      @Nullable
      public Device call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfName = CursorUtil.getColumnIndexOrThrow(_cursor, "name");
          final int _cursorIndexOfHost = CursorUtil.getColumnIndexOrThrow(_cursor, "host");
          final int _cursorIndexOfPort = CursorUtil.getColumnIndexOrThrow(_cursor, "port");
          final int _cursorIndexOfUsername = CursorUtil.getColumnIndexOrThrow(_cursor, "username");
          final int _cursorIndexOfPassword = CursorUtil.getColumnIndexOrThrow(_cursor, "password");
          final int _cursorIndexOfDomain = CursorUtil.getColumnIndexOrThrow(_cursor, "domain");
          final int _cursorIndexOfWidth = CursorUtil.getColumnIndexOrThrow(_cursor, "width");
          final int _cursorIndexOfHeight = CursorUtil.getColumnIndexOrThrow(_cursor, "height");
          final Device _result;
          if (_cursor.moveToFirst()) {
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final String _tmpName;
            _tmpName = _cursor.getString(_cursorIndexOfName);
            final String _tmpHost;
            _tmpHost = _cursor.getString(_cursorIndexOfHost);
            final int _tmpPort;
            _tmpPort = _cursor.getInt(_cursorIndexOfPort);
            final String _tmpUsername;
            _tmpUsername = _cursor.getString(_cursorIndexOfUsername);
            final String _tmpPassword;
            _tmpPassword = _cursor.getString(_cursorIndexOfPassword);
            final String _tmpDomain;
            _tmpDomain = _cursor.getString(_cursorIndexOfDomain);
            final int _tmpWidth;
            _tmpWidth = _cursor.getInt(_cursorIndexOfWidth);
            final int _tmpHeight;
            _tmpHeight = _cursor.getInt(_cursorIndexOfHeight);
            _result = new Device(_tmpId,_tmpName,_tmpHost,_tmpPort,_tmpUsername,_tmpPassword,_tmpDomain,_tmpWidth,_tmpHeight);
          } else {
            _result = null;
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @NonNull
  public static List<Class<?>> getRequiredConverters() {
    return Collections.emptyList();
  }
}
