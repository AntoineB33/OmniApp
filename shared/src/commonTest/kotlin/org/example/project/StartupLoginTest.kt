package org.example.project

import org.example.project.scheduler.sync.LOGIN_EMAIL_DOMAIN
import org.example.project.scheduler.sync.usernameToEmail
import kotlin.test.Test
import kotlin.test.assertEquals

/** A bare username maps to the synthesized Supabase email; a value that is already an email is untouched. */
class StartupLoginTest {
    @Test
    fun bare_username_gets_the_login_domain() {
        assertEquals("bob@$LOGIN_EMAIL_DOMAIN", usernameToEmail("bob"))
        assertEquals("account1@$LOGIN_EMAIL_DOMAIN", usernameToEmail("account1"))
    }

    @Test
    fun value_that_is_already_an_email_passes_through() {
        assertEquals("a@b.com", usernameToEmail("a@b.com"))
    }
}
