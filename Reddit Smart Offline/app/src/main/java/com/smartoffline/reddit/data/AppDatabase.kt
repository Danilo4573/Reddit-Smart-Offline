package com.smartoffline.reddit.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Local SQLite database. Metadata only: media files live in [com.smartoffline.reddit.storage.MediaStorage]
 * and are referenced by `media.location`. Foreign keys cascade so that deleting a post removes its
 * comments, media links and protection, and media rows are cleaned up only when no post links to them.
 */
class AppDatabase(context: Context) : SQLiteOpenHelper(context, "smart_offline.db", null, 1) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        val statements = listOf(
            """CREATE TABLE subreddits (
                name TEXT PRIMARY KEY COLLATE NOCASE,
                title TEXT NOT NULL DEFAULT '',
                icon_url TEXT,
                icon_location TEXT,
                description TEXT NOT NULL DEFAULT '',
                subscribers INTEGER NOT NULL DEFAULT 0,
                over18 INTEGER NOT NULL DEFAULT 0,
                added_at INTEGER NOT NULL,
                last_sync INTEGER NOT NULL DEFAULT 0,
                last_success INTEGER NOT NULL DEFAULT 0,
                last_status TEXT,
                last_error TEXT)""",
            """CREATE TABLE subreddit_settings (
                subreddit TEXT PRIMARY KEY COLLATE NOCASE REFERENCES subreddits(name) ON DELETE CASCADE ON UPDATE CASCADE,
                sort TEXT NOT NULL,
                time_range TEXT NOT NULL,
                post_count INTEGER NOT NULL,
                interval_hours INTEGER NOT NULL,
                dl_images INTEGER NOT NULL,
                dl_videos INTEGER NOT NULL,
                dl_gifs INTEGER NOT NULL,
                dl_text INTEGER NOT NULL,
                dl_comments INTEGER NOT NULL,
                comment_count INTEGER NOT NULL,
                reply_count INTEGER NOT NULL,
                auto_sync INTEGER NOT NULL)""",
            """CREATE TABLE posts (
                id TEXT PRIMARY KEY,
                subreddit TEXT NOT NULL COLLATE NOCASE,
                title TEXT NOT NULL,
                author TEXT NOT NULL,
                selftext TEXT NOT NULL DEFAULT '',
                url TEXT NOT NULL DEFAULT '',
                permalink TEXT NOT NULL DEFAULT '',
                domain TEXT NOT NULL DEFAULT '',
                created_utc INTEGER NOT NULL,
                score INTEGER NOT NULL DEFAULT 0,
                num_comments INTEGER NOT NULL DEFAULT 0,
                upvote_ratio REAL NOT NULL DEFAULT 0,
                over18 INTEGER NOT NULL DEFAULT 0,
                flair TEXT,
                post_type TEXT NOT NULL,
                offline_state TEXT NOT NULL,
                media_note TEXT,
                in_selection INTEGER NOT NULL DEFAULT 0,
                selection_rank INTEGER NOT NULL DEFAULT 0,
                downloaded_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                comments_fetched_at INTEGER NOT NULL DEFAULT 0,
                removed INTEGER NOT NULL DEFAULT 0)""",
            "CREATE INDEX idx_posts_sub ON posts(subreddit, in_selection)",
            "CREATE INDEX idx_posts_created ON posts(created_utc)",
            "CREATE INDEX idx_posts_score ON posts(score)",
            "CREATE INDEX idx_posts_downloaded ON posts(downloaded_at)",
            """CREATE TABLE protected_posts (
                post_id TEXT PRIMARY KEY REFERENCES posts(id) ON DELETE CASCADE,
                protected_at INTEGER NOT NULL)""",
            """CREATE TABLE comments (
                id TEXT PRIMARY KEY,
                post_id TEXT NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
                parent_id TEXT,
                depth INTEGER NOT NULL,
                position INTEGER NOT NULL,
                author TEXT NOT NULL,
                body TEXT NOT NULL,
                score INTEGER NOT NULL,
                created_utc INTEGER NOT NULL)""",
            "CREATE INDEX idx_comments_post ON comments(post_id, position)",
            """CREATE TABLE media (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                source_key TEXT NOT NULL UNIQUE,
                url TEXT NOT NULL,
                audio_url TEXT,
                kind TEXT NOT NULL,
                mime TEXT,
                width INTEGER NOT NULL DEFAULT 0,
                height INTEGER NOT NULL DEFAULT 0,
                location TEXT,
                thumb_location TEXT,
                size_bytes INTEGER NOT NULL DEFAULT 0,
                sha256 TEXT,
                state TEXT NOT NULL,
                error TEXT,
                attempts INTEGER NOT NULL DEFAULT 0,
                has_audio INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL)""",
            "CREATE INDEX idx_media_state ON media(state)",
            "CREATE INDEX idx_media_sha ON media(sha256)",
            "CREATE INDEX idx_media_location ON media(location)",
            """CREATE TABLE post_media (
                post_id TEXT NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
                media_id INTEGER NOT NULL REFERENCES media(id) ON DELETE CASCADE,
                position INTEGER NOT NULL,
                caption TEXT,
                PRIMARY KEY (post_id, media_id))""",
            "CREATE INDEX idx_post_media_media ON post_media(media_id)",
            """CREATE TABLE download_tasks (
                media_id INTEGER PRIMARY KEY REFERENCES media(id) ON DELETE CASCADE,
                state TEXT NOT NULL,
                part_path TEXT,
                bytes_done INTEGER NOT NULL DEFAULT 0,
                bytes_total INTEGER NOT NULL DEFAULT 0,
                error TEXT,
                updated_at INTEGER NOT NULL)""",
            """CREATE TABLE sync_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                subreddit TEXT,
                trigger_kind TEXT NOT NULL,
                started_at INTEGER NOT NULL,
                finished_at INTEGER NOT NULL DEFAULT 0,
                status TEXT NOT NULL,
                posts_checked INTEGER NOT NULL DEFAULT 0,
                new_posts INTEGER NOT NULL DEFAULT 0,
                downloaded_media INTEGER NOT NULL DEFAULT 0,
                failed_media INTEGER NOT NULL DEFAULT 0,
                removed_posts INTEGER NOT NULL DEFAULT 0,
                bytes INTEGER NOT NULL DEFAULT 0,
                message TEXT)""",
            "CREATE INDEX idx_history_started ON sync_history(started_at)",
            """CREATE TABLE user_preferences (
                key TEXT PRIMARY KEY,
                value TEXT)""",
        )
        statements.forEach { db.execSQL(it) }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
}
