package io.github.mexus.gcallrecorder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var observer: ContactsObserver
    private lateinit var rootStatus: TextView
    private lateinit var permContacts: TextView
    private lateinit var permPhone: TextView
    private lateinit var grantContacts: Button
    private lateinit var grantPhone: Button
    private lateinit var lastResult: TextView

    private val perms = arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.READ_PHONE_STATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rootStatus = findViewById(R.id.rootStatus)
        permContacts = findViewById(R.id.permContacts)
        permPhone = findViewById(R.id.permPhone)
        grantContacts = findViewById(R.id.grantContacts)
        grantPhone = findViewById(R.id.grantPhone)
        lastResult = findViewById(R.id.lastResult)

        setupIntervalSpinner()
        SyncScheduler.apply(this) // make the scheduled job match the stored interval

        findViewById<Button>(R.id.syncNow).setOnClickListener {
            runSync(clearing = false) { Syncer.syncNow(this) }
        }
        findViewById<Button>(R.id.clearList).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear list")
                .setMessage("Remove every number from the dialer's always-record list?")
                .setPositiveButton("Clear") { _, _ -> runSync(clearing = true) { Syncer.clear(this) } }
                .setNegativeButton("Cancel", null)
                .show()
        }

        grantContacts.setOnClickListener { requestOrOpenSettings(Manifest.permission.READ_CONTACTS) }
        grantPhone.setOnClickListener { requestOrOpenSettings(Manifest.permission.READ_PHONE_STATE) }

        // Ask up-front for anything still missing.
        val missing = perms.filter { !granted(it) }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
    }

    private fun setupIntervalSpinner() {
        val spinner = findViewById<Spinner>(R.id.intervalSpinner)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, Syncer.INTERVAL_LABELS)
            .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        val current = Syncer.syncIntervalMinutes(this)
        spinner.setSelection(Syncer.INTERVAL_MINUTES.indexOf(current).coerceAtLeast(0), false)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val minutes = Syncer.INTERVAL_MINUTES[position]
                if (minutes == Syncer.syncIntervalMinutes(this@MainActivity)) return
                Syncer.setSyncIntervalMinutes(this@MainActivity, minutes)
                SyncScheduler.apply(this@MainActivity)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPerms()
        refreshRoot()
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun refreshPerms() {
        bindPerm(permContacts, grantContacts, "Contacts", Manifest.permission.READ_CONTACTS)
        bindPerm(permPhone, grantPhone, "Phone state", Manifest.permission.READ_PHONE_STATE)
    }

    private fun bindPerm(label: TextView, btn: Button, name: String, perm: String) {
        val ok = granted(perm)
        label.text = if (ok) "$name — granted" else "$name — needed"
        label.setTextColor(ContextCompat.getColor(this, if (ok) R.color.ok else R.color.bad))
        btn.visibility = if (ok) Button.GONE else Button.VISIBLE
    }

    private fun refreshRoot() {
        rootStatus.text = "checking…"
        rootStatus.setTextColor(ContextCompat.getColor(this, R.color.bad))
        Thread {
            val ok = RootWriter().hasRoot()
            runOnUiThread {
                rootStatus.text = if (ok) "granted" else "NOT available — recording sync needs root"
                rootStatus.setTextColor(ContextCompat.getColor(this, if (ok) R.color.ok else R.color.bad))
            }
        }.start()
    }

    /** Request the permission; if the user permanently denied it, send them to app settings instead. */
    private fun requestOrOpenSettings(perm: String) {
        if (granted(perm)) return
        if (ActivityCompat.shouldShowRequestPermissionRationale(this, perm)) {
            ActivityCompat.requestPermissions(this, arrayOf(perm), 1)
        } else {
            // First ask, or "don't ask again" was chosen — try request, and offer settings.
            ActivityCompat.requestPermissions(this, arrayOf(perm), 1)
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshPerms()
        if (::observer.isInitialized.not() && granted(Manifest.permission.READ_CONTACTS)) {
            observer = ContactsObserver(this).also { it.register() }
        }
    }

    private fun runSync(clearing: Boolean, block: () -> SyncOutcome) {
        lastResult.text = if (clearing) "Clearing…" else "Syncing…"
        Thread {
            val r = runCatching { block() }.getOrElse { SyncOutcome.Wrote(WriteResult.Failed(it.message ?: "unknown error")) }
            runOnUiThread {
                val (msg, ok) = friendly(r, clearing)
                lastResult.text = msg
                lastResult.setTextColor(ContextCompat.getColor(this, if (ok) R.color.ok else R.color.bad))
            }
        }.start()
    }

    /** Human-readable outcome + whether it's a success (for colour). toString() stays for logs. */
    private fun friendly(outcome: SyncOutcome, clearing: Boolean): Pair<String, Boolean> = when (outcome) {
        is SyncOutcome.Skipped -> "Already up to date" to true
        is SyncOutcome.Deferred ->
            "Call in progress — will ${if (clearing) "clear" else "sync"} once it ends" to true
        is SyncOutcome.NoCurrent ->
            "Couldn't read the dialer's settings. Open Google Dialer once (and grant root), then retry." to false
        is SyncOutcome.Wrote -> when (val w = outcome.result) {
            is WriteResult.Ok -> (if (clearing) "List cleared" else "Contacts synced to the dialer") to true
            is WriteResult.NoRoot -> "Root not granted — approve the superuser prompt, then retry" to false
            is WriteResult.CallActive -> "Call in progress — try again after the call" to false
            is WriteResult.Failed -> "Couldn't update the list: ${w.msg}" to false
        }
    }

    override fun onStart() {
        super.onStart()
        if (::observer.isInitialized.not() && granted(Manifest.permission.READ_CONTACTS)) {
            observer = ContactsObserver(this).also { it.register() }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::observer.isInitialized) contentResolver.unregisterContentObserver(observer)
    }
}
