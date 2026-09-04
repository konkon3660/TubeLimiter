package com.tubelimiter.app.auth

/**
 * Same project the Chrome extension talks to, so one account covers both clients.
 *
 * The anon key is a public key guarded by row-level security — the extension keeps it in
 * `src/lib/config.js` for the same reason. It grants nothing on its own.
 */
const val SUPABASE_URL = "https://gigudjceurfcxcnuhlph.supabase.co"
const val SUPABASE_ANON_KEY =
    "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9." +
        "eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImdpZ3VkamNldXJmY3hjbnVobHBoIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODc4ODMwODgsImV4cCI6MjEwMzQ1OTA4OH0." +
        "mGvsQuwGEzcJ7QnmIC-AtJsoFRfA0OSLGQtTHintLRM"
