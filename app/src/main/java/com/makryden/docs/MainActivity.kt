package com.makryden.docs

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView

/**
 * Makryden Docs - hybrid native app.
 * Native shell: Toolbar + BottomNavigationView + native lists.
 * Editing surfaces (writer/notes/pdf) live in EditorActivity as WebView canvas.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val AUTH_REQUEST = 1001
    }

    private lateinit var bottomNav: BottomNavigationView
    private lateinit var toolbar: Toolbar
    private var currentTab: Int = R.id.nav_documents

    private val authLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                updateAccountUi()
                refreshCurrentTab()
            } else {
                // User backed out of auth; if still not signed in, keep prompting gently
                if (!SessionStore(this).isSignedIn()) {
                    Toast.makeText(this, "Sign in to sync your documents", Toast.LENGTH_SHORT).show()
                }
            }
        }

    private val pdfPickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val uri = result.data?.data
                if (uri != null) {
                    val viewerIntent = Intent(this, PdfViewerActivity::class.java)
                    viewerIntent.putExtra(PdfViewerActivity.EXTRA_TITLE, "PDF")
                    viewerIntent.putExtra(PdfViewerActivity.EXTRA_URI, uri.toString())
                    // Persist permission so the viewer can read it
                    try {
                        contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (e: Exception) { }
                    startActivity(viewerIntent)
                }
            }
        }

    /** Open the system file picker for PDFs (called from PdfListFragment FAB). */
    fun openPdfPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.type = "application/pdf"
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        pdfPickerLauncher.launch(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = "Makryden Docs"

        bottomNav = findViewById(R.id.bottom_nav)
        bottomNav.setOnItemSelectedListener { item ->
            currentTab = item.itemId
            val fragment: Fragment = when (item.itemId) {
                R.id.nav_notes -> NotesListFragment()
                R.id.nav_pdfs -> PdfListFragment()
                else -> DocumentListFragment()
            }
            supportActionBar?.title = when (item.itemId) {
                R.id.nav_notes -> "Notes"
                R.id.nav_pdfs -> "PDFs"
                else -> "Documents"
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .commit()
            true
        }

        // Default tab
        if (savedInstanceState == null) {
            bottomNav.selectedItemId = R.id.nav_documents
            handleIntent(intent)
        }

        // Require sign-in for sync
        if (!SessionStore(this).isSignedIn()) {
            promptSignIn()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /**
     * Share-intent handling: PDFs shared from other apps open the native
     * PDF viewer, shared text opens the native note editor.
     */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            val type = intent.type ?: ""
            when {
                type == "application/pdf" -> {
                    val uri = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
                    val viewerIntent = Intent(this, PdfViewerActivity::class.java)
                    viewerIntent.putExtra(PdfViewerActivity.EXTRA_TITLE, "Shared PDF")
                    if (uri != null) {
                        viewerIntent.putExtra(PdfViewerActivity.EXTRA_URI, uri.toString())
                    }
                    startActivity(viewerIntent)
                }
                type.startsWith("text/") -> {
                    val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
                    // stash for the note editor to pick up
                    getSharedPreferences("mk_share", MODE_PRIVATE).edit()
                        .putString("shared_text", sharedText).apply()
                    val noteIntent = Intent(this, NoteEditorActivity::class.java)
                    noteIntent.putExtra(NoteEditorActivity.EXTRA_TITLE, "New note")
                    startActivity(noteIntent)
                }
            }
        }
    }

    private fun promptSignIn() {
        val intent = Intent(this, AuthActivity::class.java)
        authLauncher.launch(intent)
    }

    private fun refreshCurrentTab() {
        val fragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
        if (fragment is BaseListFragment) {
            fragment.refresh()
        }
    }

    private fun updateAccountUi() {
        // Rebuild the options menu to reflect sign-in state
        invalidateOptionsMenu()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val session = SessionStore(this)
        val signInItem = menu.findItem(R.id.action_sign_in)
        val signOutItem = menu.findItem(R.id.action_sign_out)
        val accountItem = menu.findItem(R.id.action_account)
        if (session.isSignedIn()) {
            signInItem?.isVisible = false
            signOutItem?.isVisible = true
            accountItem?.isVisible = true
            accountItem?.title = session.getEmail() ?: "Account"
        } else {
            signInItem?.isVisible = true
            signOutItem?.isVisible = false
            accountItem?.isVisible = false
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_sign_in -> {
                promptSignIn()
                true
            }
            R.id.action_sign_out -> {
                SessionStore(this).clear()
                Toast.makeText(this, "Signed out", Toast.LENGTH_SHORT).show()
                updateAccountUi()
                refreshCurrentTab()
                promptSignIn()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
