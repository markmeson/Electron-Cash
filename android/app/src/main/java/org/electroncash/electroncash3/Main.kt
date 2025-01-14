package org.electroncash.electroncash3

import android.app.Activity
import androidx.lifecycle.Observer
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.text.Html
import android.text.SpannableStringBuilder
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import com.chaquo.python.PyException
import kotlinx.android.synthetic.main.change_password.*
import kotlinx.android.synthetic.main.main.*
import kotlin.reflect.KClass


// Drawer navigation
val ACTIVITIES = HashMap<Int, KClass<out Activity>>().apply {
    put(R.id.navSettings, SettingsActivity::class)
    put(R.id.navNetwork, NetworkActivity::class)
    put(R.id.navConsole, ECConsoleActivity::class)
}

// Bottom navigation
val FRAGMENTS = HashMap<Int, KClass<out Fragment>>().apply {
    put(R.id.navNoWallet, NoWalletFragment::class)
    put(R.id.navTransactions, TransactionsFragment::class)
    put(R.id.navRequests, RequestsFragment::class)
    put(R.id.navAddresses, AddressesFragment::class)
    put(R.id.navContacts, ContactsFragment::class)
}

interface MainFragment


class MainActivity : AppCompatActivity() {
    var cleanStart = true
    var newIntent = true
    var walletName: String? = null

    override fun onCreate(state: Bundle?) {
        // Remove splash screen: doesn't work if called after super.onCreate.
        setTheme(R.style.AppTheme_NoActionBar)

        // If the system language changes while the app is running, the activity will be
        // restarted, but not the process.
        @Suppress("DEPRECATION")
        libMod("i18n").callAttr("set_language", resources.configuration.locale.toString())

        // If the wallet name doesn't match, the process has probably been restarted, so
        // ignore the UI state, including all dialogs.
        if (state != null) {
            walletName = state.getString("walletName")
            cleanStart = (walletName != daemonModel.walletName)
        }
        super.onCreate(if (!cleanStart) state else null)

        setContentView(R.layout.main)
        setSupportActionBar(toolbar)
        supportActionBar!!.apply {
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_menu_24dp)
        }

        navDrawer.setNavigationItemSelectedListener { onDrawerItemSelected(it) }
        navBottom.setOnNavigationItemSelectedListener {
            showFragment(it.itemId)
            true
        }

        daemonUpdate.observe(this, Observer { refresh() })
        settings.getString("base_unit").observe(this, Observer { updateToolbar() })
        fiatUpdate.observe(this, Observer { updateToolbar() })
    }

    fun refresh() {
        updateToolbar()
        updateDrawer()

        val newWalletName = daemonModel.walletName
        if (cleanStart || (newWalletName != walletName)) {
            walletName = newWalletName
            invalidateOptionsMenu()
            clearFragments()
            navBottom.selectedItemId = if (walletName == null) R.id.navNoWallet
                                       else R.id.navTransactions
        }
    }

    override fun onBackPressed() {
        if (drawer.isDrawerOpen(navDrawer)) {
            closeDrawer()
        } else {
            super.onBackPressed()
        }
    }

    fun updateToolbar() {
        val title = daemonModel.walletName ?: getString(R.string.No_wallet)

        val subtitle: String
        if (! daemonModel.isConnected()) {
            subtitle = getString(R.string.offline)
        } else {
            val wallet = daemonModel.wallet
            val localHeight = daemonModel.network.callAttr("get_local_height").toInt()
            val serverHeight = daemonModel.network.callAttr("get_server_height").toInt()
            if (localHeight < serverHeight) {
                subtitle = "${getString(R.string.synchronizing)} $localHeight / $serverHeight"
            } else if (wallet == null) {
                subtitle = getString(R.string.online)
            } else if (wallet.callAttr("is_up_to_date").toBoolean()) {
                // get_balance returns the tuple (confirmed, unconfirmed, unmatured)
                val balance = wallet.callAttr("get_balance").asList().get(0).toLong()
                subtitle = formatSatoshisAndFiat(balance)
            } else {
                subtitle = getString(R.string.synchronizing)
            }
        }

        if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
            setTitle(title)
            supportActionBar!!.setSubtitle(subtitle)
        } else {
            // Landscape subtitle is too small, so combine it with the title.
            setTitle("$title – $subtitle")
        }
    }

    fun openDrawer() {
        drawer.openDrawer(navDrawer)
    }

    fun closeDrawer() {
        drawer.closeDrawer(navDrawer)
    }

    fun updateDrawer() {
        val loadedWalletName = daemonModel.walletName
        val menu = navDrawer.menu
        menu.clear()

        // New menu items are added at the bottom regardless of their group ID, so we inflate
        // the fixed items in two parts.
        navDrawer.inflateMenu(R.menu.nav_drawer_1)
        for (walletName in daemonModel.listWallets()) {
            val item = menu.add(R.id.navWallets, Menu.NONE, Menu.NONE, walletName)
            item.setIcon(R.drawable.ic_wallet_24dp)
            if (walletName == loadedWalletName) {
                item.setCheckable(true)
                item.setChecked(true)
            }
        }
        navDrawer.inflateMenu(R.menu.nav_drawer_2)
    }

    fun onDrawerItemSelected(item: MenuItem): Boolean {
        val activityCls = ACTIVITIES[item.itemId]
        if (activityCls != null) {
            startActivity(Intent(this, activityCls.java))
        } else if (item.itemId == R.id.navNewWallet) {
            showDialog(this, NewWalletDialog1())
        } else if (item.itemId == Menu.NONE) {
            val walletName = item.title.toString()
            if (walletName != daemonModel.walletName) {
                showDialog(this, OpenWalletDialog().apply { arguments = Bundle().apply {
                    putString("walletName", walletName)
                }})
            }
        } else if (item.itemId == R.id.navAbout) {
            showDialog(this, AboutDialog())
        } else {
            throw Exception("Unknown item $item")
        }
        closeDrawer()
        return false
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val wallet = daemonModel.wallet
        if (wallet != null) {
            menuInflater.inflate(R.menu.wallet, menu)
            menu.findItem(R.id.menuUseChange)!!.isChecked =
                wallet.get("use_change")!!.toBoolean()
            if (!wallet.callAttr("has_seed").toBoolean()) {
                menu.findItem(R.id.menuShowSeed).isEnabled = false
            }
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> openDrawer()
            R.id.menuUseChange -> {
                item.isChecked = !item.isChecked
                daemonModel.wallet!!.put("use_change", item.isChecked)
                val storage = daemonModel.wallet!!.get("storage")!!
                storage.callAttr("put", "use_change", item.isChecked)
                storage.callAttr("write")
            }
            R.id.menuChangePassword -> showDialog(this, ChangePasswordDialog())
            R.id.menuShowSeed-> { showDialog(this, ShowSeedPasswordDialog()) }
            R.id.menuDelete -> showDialog(this, DeleteWalletDialog())
            R.id.menuClose -> showDialog(this, CloseWalletDialog())
            else -> throw Exception("Unknown item $item")
        }
        return true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("newIntent", newIntent)
        outState.putString("walletName", walletName)
    }

    override fun onRestoreInstanceState(state: Bundle) {
        if (!cleanStart) {
            super.onRestoreInstanceState(state)
        }
        newIntent = state.getBoolean("newIntent")
    }

    override fun onPostCreate(state: Bundle?) {
        super.onPostCreate(if (!cleanStart) state else null)
    }

    // setIntent only takes effect on the current instance of the activity: after a rotation,
    // the original intent will be restored.
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        newIntent = true
    }

    override fun onResume() {
        super.onResume()
        if (newIntent) {
            newIntent = false
            val uri = intent?.data
            if (uri != null) {
                if (daemonModel.wallet == null) {
                    toast(R.string.no_wallet_is_open_)
                    openDrawer()
                } else {
                    val dialog = findDialog(this, SendDialog::class)
                    if (dialog != null) {
                        dialog.onUri(uri.toString())
                    } else {
                        try {
                            showDialog(this, SendDialog().apply {
                                arguments = Bundle().apply {
                                    putString("uri", uri.toString())
                                }
                            })
                        } catch (e: ToastException) { e.show() }
                    }
                }
            }
        }
    }

    override fun onResumeFragments() {
        super.onResumeFragments()
        showFragment(navBottom.selectedItemId)
        if (cleanStart) {
            cleanStart = false
            if (daemonModel.wallet == null) {
                openDrawer()
            }
        }
    }

    fun showFragment(id: Int) {
        val ft = supportFragmentManager.beginTransaction()
        val newFrag = getOrCreateFragment(id)
        for (frag in supportFragmentManager.fragments) {
            if (frag is MainFragment && frag !== newFrag) {
                ft.detach(frag)
            }
        }
        ft.attach(newFrag)
        ft.commit()

        navBottom.visibility = if (newFrag is NoWalletFragment) View.GONE else View.VISIBLE
    }

    fun getFragment(id: Int): Fragment? {
        return supportFragmentManager.findFragmentByTag(fragTag(id))
    }

    fun getOrCreateFragment(id: Int): Fragment {
        var frag = getFragment(id)
        if (frag != null) {
            return frag
        } else {
            frag = FRAGMENTS[id]!!.java.newInstance()
            supportFragmentManager.beginTransaction()
                .add(flContent.id, frag, fragTag(id))
                .commit()
            return frag
        }
    }

    fun clearFragments() {
        val ft = supportFragmentManager.beginTransaction()
        for (id in FRAGMENTS.keys) {
            val frag = getFragment(id)
            if (frag != null) {
                ft.remove(frag)
            }
        }
        ft.commit()
    }

    fun fragTag(id: Int) = "MainFragment:$id"
}


class NoWalletFragment : Fragment(), MainFragment {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.no_wallet, container, false)
    }
}


class AboutDialog : AlertDialogFragment() {
    override fun onBuildDialog(builder: AlertDialog.Builder) {
        with (builder) {
            val version = app.packageManager.getPackageInfo(app.packageName, 0).versionName
            setTitle(getString(R.string.app_name) + " " + version)
            val message = SpannableStringBuilder(getString(R.string.copyright_2019) + "\n\n")
            @Suppress("DEPRECATION")
            message.append(Html.fromHtml(getString(R.string.made_with)))
            setMessage(message)
        }
    }

    override fun onShowDialog() {
        dialog.findViewById<TextView>(android.R.id.message)!!.movementMethod =
            LinkMovementMethod.getInstance()
    }
}


class DeleteWalletDialog : TaskLauncherDialog<Unit>() {
    override fun onBuildDialog(builder: AlertDialog.Builder) {
        val message = getString(R.string.do_you_want_to_delete, daemonModel.walletName) +
                      "\n\n" + getString(R.string.if_your)
        builder.setTitle(R.string.confirm_delete)
            .setMessage(message)
            .setPositiveButton(R.string.delete, null)
            .setNegativeButton(android.R.string.cancel, null)
    }

    override fun doInBackground() {
        daemonModel.commands.callAttr("delete_wallet", daemonModel.walletName)
    }

    override fun onPostExecute(result: Unit) {
        (activity as MainActivity).openDrawer()
    }
}


class OpenWalletDialog : PasswordDialog<Unit>() {
    override fun onPassword(password: String) {
        daemonModel.loadWallet(arguments!!.getString("walletName")!!, password)
    }
}


class CloseWalletDialog : TaskDialog<Unit>() {
    override fun doInBackground() {
        daemonModel.commands.callAttr("close_wallet")
    }

    override fun onPostExecute(result: Unit) {
        (activity as MainActivity).openDrawer()
    }
}


class ChangePasswordDialog : AlertDialogFragment() {
    override fun onBuildDialog(builder: AlertDialog.Builder) {
        builder.setTitle(R.string.Change_password)
            .setView(R.layout.change_password)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
    }

    override fun onShowDialog() {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try {
                val currentPassword = etCurrentPassword.text.toString()
                val newPassword = confirmPassword(dialog)
                try {
                    daemonModel.wallet!!.callAttr("update_password",
                                                  currentPassword, newPassword, true)
                    toast(R.string.password_was, Toast.LENGTH_SHORT)
                    dismiss()
                } catch (e: PyException) {
                    throw if (e.message!!.startsWith("InvalidPassword"))
                        ToastException(R.string.incorrect_password, Toast.LENGTH_SHORT) else e
                }
            } catch (e: ToastException) {
                e.show()
            }
        }
    }
}


data class ShowSeedResult(val seed: String, val passphrase: String)

class ShowSeedPasswordDialog : PasswordDialog<ShowSeedResult>() {
    override fun onPassword(password: String): ShowSeedResult {
        val keystore = daemonModel.wallet!!.callAttr("get_keystore")!!
        return ShowSeedResult(keystore.callAttr("get_seed", password).toString(),
                              keystore.callAttr("get_passphrase", password).toString())
    }

    override fun onPostExecute(result: ShowSeedResult) {
        showDialog(activity!!, SeedDialog().apply { arguments = Bundle().apply {
            putString("seed", result.seed)
            putString("passphrase", result.passphrase)
        }})
    }
}


class SeedDialog : AlertDialogFragment() {
    override fun onBuildDialog(builder: AlertDialog.Builder) {
        builder.setTitle(R.string.Wallet_seed)
            .setView(R.layout.new_wallet_2)
            .setPositiveButton(android.R.string.ok, null)
    }

    override fun onShowDialog() {
        setupSeedDialog(this)
    }
}
