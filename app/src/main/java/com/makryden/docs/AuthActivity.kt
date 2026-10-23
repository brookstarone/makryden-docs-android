package com.makryden.docs

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText

/**
 * Native sign-in screen. Stores the session in mk-portal-session format
 * so the bundled web tools adopt it automatically.
 */
class AuthActivity : AppCompatActivity() {

    private var isLogin = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auth)

        val tabs: TabLayout = findViewById(R.id.auth_tabs)
        val emailInput: TextInputEditText = findViewById(R.id.email_input)
        val passwordInput: TextInputEditText = findViewById(R.id.password_input)
        val submitButton: Button = findViewById(R.id.submit_button)
        val progress: ProgressBar = findViewById(R.id.auth_progress)
        val title: TextView = findViewById(R.id.auth_title)

        tabs.addTab(tabs.newTab().setText("Log in"))
        tabs.addTab(tabs.newTab().setText("Create account"))

        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                isLogin = tab.position == 0
                title.text = if (isLogin) "Welcome back" else "Create your account"
                submitButton.text = if (isLogin) "Log in" else "Create account"
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        submitButton.setOnClickListener {
            val email = emailInput.text?.toString()?.trim() ?: ""
            val password = passwordInput.text?.toString() ?: ""
            if (email.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Enter your email and password", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (password.length < 6) {
                Toast.makeText(this, "Password must be at least 6 characters", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            progress.visibility = View.VISIBLE
            submitButton.isEnabled = false
            Thread {
                try {
                    val auth = if (isLogin) {
                        SupabaseClient.signIn(email, password)
                    } else {
                        SupabaseClient.signUp(email, password)
                    }
                    SessionStore(this).save(
                        auth.accessToken,
                        auth.refreshToken,
                        auth.expiresIn,
                        auth.email
                    )
                    runOnUiThread {
                        progress.visibility = View.GONE
                        Toast.makeText(this, "Signed in", Toast.LENGTH_SHORT).show()
                        setResult(RESULT_OK)
                        finish()
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        progress.visibility = View.GONE
                        submitButton.isEnabled = true
                        Toast.makeText(this, "Sign-in failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }
    }

    companion object {
        fun startForResult(activity: AppCompatActivity, requestCode: Int) {
            val intent = Intent(activity, AuthActivity::class.java)
            activity.startActivityForResult(intent, requestCode)
        }
    }
}
