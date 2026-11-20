package com.example.aichat;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

public class Store extends SQLiteOpenHelper {

    private static final String DB_NAME = "chat.db";
    private static final int DB_VERSION = 1;

    public Store(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE conversations("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "title TEXT,"
                + "created_at INTEGER,"
                + "updated_at INTEGER)");
        db.execSQL("CREATE TABLE messages("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "conversation_id INTEGER,"
                + "role TEXT,"
                + "content TEXT,"
                + "created_at INTEGER)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS conversations");
        db.execSQL("DROP TABLE IF EXISTS messages");
        onCreate(db);
    }

    public long newConversation() {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("title", "新会话");
        cv.put("created_at", System.currentTimeMillis());
        cv.put("updated_at", System.currentTimeMillis());
        long id = db.insert("conversations", null, cv);
        db.close();
        return id;
    }

    public long getLatestConversationId() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT id FROM conversations ORDER BY updated_at DESC LIMIT 1", null);
        long id = -1;
        if (c.moveToFirst()) {
            id = c.getLong(0);
        }
        c.close();
        db.close();
        return id;
    }

    public List<String[]> listConversations() {
        List<String[]> result = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT id, title FROM conversations ORDER BY updated_at DESC", null);
        while (c.moveToNext()) {
            result.add(new String[]{String.valueOf(c.getLong(0)), c.getString(1)});
        }
        c.close();
        db.close();
        return result;
    }

    public void deleteConversation(long id) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("messages", "conversation_id=?", new String[]{String.valueOf(id)});
        db.delete("conversations", "id=?", new String[]{String.valueOf(id)});
        db.close();
    }

    public void saveMessage(long convId, String role, String content) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("conversation_id", convId);
        cv.put("role", role);
        cv.put("content", content);
        cv.put("created_at", System.currentTimeMillis());
        db.insert("messages", null, cv);
        ContentValues uv = new ContentValues();
        uv.put("updated_at", System.currentTimeMillis());
        db.update("conversations", uv, "id=?", new String[]{String.valueOf(convId)});
        db.close();
    }

    public List<String[]> loadMessages(long convId) {
        List<String[]> result = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT role, content FROM messages WHERE conversation_id=? ORDER BY id ASC",
                new String[]{String.valueOf(convId)});
        while (c.moveToNext()) {
            result.add(new String[]{c.getString(0), c.getString(1)});
        }
        c.close();
        db.close();
        return result;
    }

    public void updateTitle(long convId, String title) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("title", title);
        db.update("conversations", cv, "id=?", new String[]{String.valueOf(convId)});
        db.close();
    }
}
