/*
 * Copyright (c) 2017 m2049r
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.sumokoin.sumowallet;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.IBinder;
import android.support.annotation.NonNull;
import android.support.design.widget.NavigationView;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentTransaction;
import android.support.v4.view.GravityCompat;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.app.ActionBarDrawerToggle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.sumokoin.sumowallet.data.BarcodeData;
import com.sumokoin.sumowallet.data.TxData;
import com.sumokoin.sumowallet.data.UserNotes;
import com.sumokoin.sumowallet.dialog.CreditsFragment;
import com.sumokoin.sumowallet.dialog.HelpFragment;
import com.sumokoin.sumowallet.fragment.send.SendAddressWizardFragment;
import com.sumokoin.sumowallet.fragment.send.SendFragment;
import com.sumokoin.sumowallet.ledger.LedgerProgressDialog;
import com.sumokoin.sumowallet.model.PendingTransaction;
import com.sumokoin.sumowallet.model.TransactionInfo;
import com.sumokoin.sumowallet.model.Wallet;
import com.sumokoin.sumowallet.model.WalletManager;
import com.sumokoin.sumowallet.service.WalletService;
import com.sumokoin.sumowallet.util.Helper;
import com.sumokoin.sumowallet.util.MoneroThreadPoolExecutor;
import com.sumokoin.sumowallet.widget.Toolbar;

import java.util.ArrayList;
import java.util.List;

import timber.log.Timber;

public class WalletActivity extends BaseActivity implements WalletFragment.Listener,
        WalletService.Observer, SendFragment.Listener, TxFragment.Listener,
        GenerateReviewFragment.ListenerWithWallet,
        GenerateReviewFragment.Listener,
        GenerateReviewFragment.PasswordChangedListener,
        ScannerFragment.OnScannedListener, ReceiveFragment.Listener,
        SendAddressWizardFragment.OnScanListener,
        WalletFragment.DrawerLocker,
        NavigationView.OnNavigationItemSelectedListener {

    public static final String REQUEST_ID = "id";
    public static final String REQUEST_PW = "pw";
    public static final String REQUEST_FINGERPRINT_USED = "fingerprint";
    public static final String REQUEST_STREETMODE = "streetmode";
    public static final String REQUEST_URI = "uri";

    private NavigationView accountsView;
    private DrawerLayout drawer;
    private ActionBarDrawerToggle drawerToggle;

    private Toolbar toolbar;
    private boolean needVerifyIdentity;
    private boolean requestStreetMode = false;

    private String password;

    private String uri = null;

    private long streetMode = 0;

    @Override
    public void onPasswordChanged(String newPassword) {
        password = newPassword;
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public void setToolbarButton(int type) {
        toolbar.setButton(type);
    }

    @Override
    public void setTitle(String title, String subtitle) {
        toolbar.setTitle(title, subtitle);
    }

    @Override
    public void setTitle(String title) {
        Timber.d("setTitle:%s.", title);
        toolbar.setTitle(title);
    }

    @Override
    public void setSubtitle(String subtitle) {
        toolbar.setSubtitle(subtitle);
    }

    private boolean synced = false;

    @Override
    public boolean isSynced() {
        return synced;
    }

    @Override
    public boolean isStreetMode() {
        return streetMode > 0;
    }

    private void enableStreetMode(boolean enable) {
        if (enable) {
            needVerifyIdentity = true;
            streetMode = getWallet().getDaemonBlockChainHeight();
        } else {
            streetMode = 0;
        }
        final WalletFragment walletFragment = (WalletFragment)
                getSupportFragmentManager().findFragmentByTag(WalletFragment.class.getName());
        if (walletFragment != null) walletFragment.resetDismissedTransactions();
        forceUpdate();
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                updateAccountsBalance();
            }
        });
    }

    @Override
    public long getStreetModeHeight() {
        return streetMode;
    }

    @Override
    public boolean isWatchOnly() {
        return getWallet().isWatchOnly();
    }

    @Override
    public String getTxKey(String txId) {
        return getWallet().getTxKey(txId);
    }

    @Override
    public String getTxNotes(String txId) {
        return getWallet().getUserNote(txId);
    }

    @Override
    public boolean setTxNotes(String txId, String txNotes) {
        return getWallet().setUserNote(txId, txNotes);
    }

    @Override
    public String getTxAddress(int major, int minor) {
        return getWallet().getSubaddress(major, minor);
    }

    @Override
    protected void onStart() {
        super.onStart();
        Timber.d("onStart()");
    }

    private void startWalletService() {
        Bundle extras = getIntent().getExtras();
        if (extras != null) {
            acquireWakeLock();
            String walletId = extras.getString(REQUEST_ID);
            needVerifyIdentity = extras.getBoolean(REQUEST_FINGERPRINT_USED);
            // we can set the streetmode height AFTER opening the wallet
            requestStreetMode = extras.getBoolean(REQUEST_STREETMODE);
            password = extras.getString(REQUEST_PW);
            uri = extras.getString(REQUEST_URI);
            connectWalletService(walletId, password);
        } else {
            finish();
            //throw new IllegalStateException("No extras passed! Panic!");
        }
    }

    private void stopWalletService() {
        disconnectWalletService();
        releaseWakeLock();
    }

    @Override
    protected void onStop() {
        Timber.d("onStop()");
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        Timber.d("onDestroy()");
        if ((mBoundService != null) && (getWallet() != null)) {
            saveWallet();
        }
        stopWalletService();
        if (drawer != null) drawer.removeDrawerListener(drawerToggle);
        super.onDestroy();
    }

    @Override
    public boolean hasWallet() {
        return haveWallet;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem renameItem = menu.findItem(R.id.action_rename);
        if (renameItem != null)
            renameItem.setVisible(hasWallet() && getWallet().isSynchronized());
        MenuItem streetmodeItem = menu.findItem(R.id.action_streetmode);
        if (streetmodeItem != null)
            if (isStreetMode()) {
                streetmodeItem.setIcon(R.drawable.gunther_csi_24dp);
            } else {
                streetmodeItem.setIcon(R.drawable.gunther_24dp);
            }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_info:
                onWalletDetails();
                return true;
            case R.id.action_credits:
                CreditsFragment.display(getSupportFragmentManager());
                return true;
            case R.id.action_share:
                onShareTxInfo();
                return true;
            case R.id.action_help_tx_info:
                HelpFragment.display(getSupportFragmentManager(), R.string.help_tx_details);
                return true;
            case R.id.action_help_wallet:
                HelpFragment.display(getSupportFragmentManager(), R.string.help_wallet);
                return true;
            case R.id.action_details_help:
                HelpFragment.display(getSupportFragmentManager(), R.string.help_details);
                return true;
            case R.id.action_details_changepw:
                onWalletChangePassword();
                return true;
            case R.id.action_help_send:
                HelpFragment.display(getSupportFragmentManager(), R.string.help_send);
                return true;
            case R.id.action_rename:
                onAccountRename();
                return true;
            case R.id.action_streetmode:
                if (isStreetMode()) { // disable streetmode
                    onDisableStreetMode();
                } else {
                    onEnableStreetMode();
                }
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void updateStreetMode() {
        if (isStreetMode()) {
            toolbar.setBackgroundResource(R.drawable.backgound_toolbar_streetmode);
        } else {
            showNet();
        }
        invalidateOptionsMenu();
    }

    private void onEnableStreetMode() {
        enableStreetMode(true);
        updateStreetMode();
    }

    private void onDisableStreetMode() {
        Helper.promptPassword(WalletActivity.this, getWallet().getName(), false, new Helper.PasswordAction() {
            @Override
            public void action(String walletName, String password, boolean fingerprintUsed) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        enableStreetMode(false);
                        updateStreetMode();
                    }
                });
            }
        });
    }


    public void onWalletChangePassword() {
        try {
            GenerateReviewFragment detailsFragment = (GenerateReviewFragment)
                    getSupportFragmentManager().findFragmentById(R.id.fragment_container);
            AlertDialog dialog = detailsFragment.createChangePasswordDialog();
            if (dialog != null) {
                Helper.showKeyboard(dialog);
                dialog.show();
            }
        } catch (ClassCastException ex) {
            Timber.w("onWalletChangePassword() called, but no GenerateReviewFragment active");
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Timber.d("onCreate()");
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            // activity restarted
            // we don't want that - finish it and fall back to previous activity
            finish();
            return;
        }

        setContentView(R.layout.activity_wallet);
        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayShowTitleEnabled(false);

        toolbar.setOnButtonListener(new Toolbar.OnButtonListener() {
            @Override
            public void onButton(int type) {
                switch (type) {
                    case Toolbar.BUTTON_BACK:
                        onDisposeRequest();
                        onBackPressed();
                        break;
                    case Toolbar.BUTTON_CANCEL:
                        onDisposeRequest();
                        Helper.hideKeyboard(WalletActivity.this);
                        WalletActivity.super.onBackPressed();
                        break;
                    case Toolbar.BUTTON_CLOSE:
                        finish();
                        break;
                    case Toolbar.BUTTON_CREDITS:
                        Toast.makeText(WalletActivity.this, getString(R.string.label_credits), Toast.LENGTH_SHORT).show();
                    case Toolbar.BUTTON_NONE:
                    default:
                        Timber.e("Button " + type + "pressed - how can this be?");
                }
            }
        });

        drawer = findViewById(R.id.drawer_layout);
        drawerToggle = new ActionBarDrawerToggle(this, drawer, toolbar, 0, 0);
        drawer.addDrawerListener(drawerToggle);
        drawerToggle.syncState();
        setDrawerEnabled(false); // disable until synced

        accountsView = findViewById(R.id.accounts_nav);
        accountsView.setNavigationItemSelectedListener(this);

        showNet();

        Fragment walletFragment = new WalletFragment();
        getSupportFragmentManager().beginTransaction()
                .add(R.id.fragment_container, walletFragment, WalletFragment.class.getName()).commit();
        Timber.d("fragment added");

        startWalletService();
        Timber.d("onCreate() done.");
    }

    public void showNet() {
        switch (WalletManager.getInstance().getNetworkType()) {
            case NetworkType_Mainnet:
                toolbar.setBackgroundResource(R.drawable.backgound_toolbar_mainnet);
                break;
            case NetworkType_Testnet:
                toolbar.setBackgroundResource(R.color.colorPrimaryDark);
                break;
            case NetworkType_Stagenet:
                toolbar.setBackgroundResource(R.color.colorPrimaryDark);
                break;
            default:
                throw new IllegalStateException("Unsupported Network: " + WalletManager.getInstance().getNetworkType());
        }
    }

    @Override
    public Wallet getWallet() {
        if (mBoundService == null) throw new IllegalStateException("WalletService not bound.");
        return mBoundService.getWallet();
    }

    private WalletService mBoundService = null;
    private boolean mIsBound = false;

    private ServiceConnection mConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder service) {
            // This is called when the connection with the service has been
            // established, giving us the service object we can use to
            // interact with the service.  Because we have bound to a explicit
            // service that we know is running in our own process, we can
            // cast its IBinder to a concrete class and directly access it.
            mBoundService = ((WalletService.WalletServiceBinder) service).getService();
            mBoundService.setObserver(WalletActivity.this);
            Bundle extras = getIntent().getExtras();
            if (extras != null) {
                String walletId = extras.getString(REQUEST_ID);
                if (walletId != null) {
                    setTitle(walletId, getString(R.string.status_wallet_connecting));
                }
            }
            updateProgress();
            Timber.d("CONNECTED");
        }

        public void onServiceDisconnected(ComponentName className) {
            // This is called when the connection with the service has been
            // unexpectedly disconnected -- that is, its process crashed.
            // Because it is running in our same process, we should never
            // see this happen.
            mBoundService = null;
            setTitle(getString(R.string.wallet_activity_name), getString(R.string.status_wallet_disconnected));
            Timber.d("DISCONNECTED");
        }
    };

    void connectWalletService(String walletName, String walletPassword) {
        // Establish a connection with the service.  We use an explicit
        // class name because we want a specific service implementation that
        // we know will be running in our own process (and thus won't be
        // supporting component replacement by other applications).
        Intent intent = new Intent(getApplicationContext(), WalletService.class);
        intent.putExtra(WalletService.REQUEST_WALLET, walletName);
        intent.putExtra(WalletService.REQUEST, WalletService.REQUEST_CMD_LOAD);
        intent.putExtra(WalletService.REQUEST_CMD_LOAD_PW, walletPassword);
        startService(intent);
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
        mIsBound = true;
        Timber.d("BOUND");
    }

    void disconnectWalletService() {
        if (mIsBound) {
            // Detach our existing connection.
            mBoundService.setObserver(null);
            unbindService(mConnection);
            mIsBound = false;
            Timber.d("UNBOUND");
        }
    }

    @Override
    protected void onPause() {
        Timber.d("onPause()");
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Timber.d("onResume()");
    }

    public void saveWallet() {
        if (mIsBound) { // no point in talking to unbound service
            Intent intent = new Intent(getApplicationContext(), WalletService.class);
            intent.putExtra(WalletService.REQUEST, WalletService.REQUEST_CMD_STORE);
            startService(intent);
            Timber.d("STORE request sent");
        } else {
            Timber.e("Service not bound");
        }
    }

//////////////////////////////////////////
// WalletFragment.Listener
//////////////////////////////////////////

    @Override
    public boolean hasBoundService() {
        return mBoundService != null;
    }

    @Override
    public Wallet.ConnectionStatus getConnectionStatus() {
        return mBoundService.getConnectionStatus();
    }

    @Override
    public long getDaemonHeight() {
        return mBoundService.getDaemonHeight();
    }

    @Override
    public void onSendRequest() {
        replaceFragment(SendFragment.newInstance(uri), null, null);
        uri = null; // only use uri once
    }

    @Override
    public void onTxDetailsRequest(TransactionInfo info) {
        Bundle args = new Bundle();
        args.putParcelable(TxFragment.ARG_INFO, info);
        replaceFragment(new TxFragment(), null, args);
    }

    @Override
    public void forceUpdate() {
        try {
            onRefreshed(getWallet(), true);
        } catch (IllegalStateException ex) {
            Timber.e(ex.getLocalizedMessage());
        }
    }

///////////////////////////
// WalletService.Observer
///////////////////////////

    private int numAccounts = -1;

    // refresh and return true if successful
    @Override
    public boolean onRefreshed(final Wallet wallet, final boolean full) {
        Timber.d("onRefreshed()");
        runOnUiThread(new Runnable() {
            public void run() {
                updateAccountsBalance();
            }
        });
        if (numAccounts != wallet.getNumAccounts()) {
            numAccounts = wallet.getNumAccounts();
            runOnUiThread(new Runnable() {
                public void run() {
                    updateAccountsList();
                }
            });
        }
        try {
            final WalletFragment walletFragment = (WalletFragment)
                    getSupportFragmentManager().findFragmentByTag(WalletFragment.class.getName());
            if (wallet.isSynchronized()) {
                Timber.d("onRefreshed() synced");
                releaseWakeLock(RELEASE_WAKE_LOCK_DELAY); // the idea is to stay awake until synced
                if (!synced) { // first sync
                    onProgress(-1);
                    saveWallet(); // save on first sync
                    synced = true;
                    runOnUiThread(new Runnable() {
                        public void run() {
                            walletFragment.onSynced();
                        }
                    });
                }
            }
            runOnUiThread(new Runnable() {
                public void run() {
                    walletFragment.onRefreshed(wallet, full);
                }
            });
            return true;
        } catch (ClassCastException ex) {
            // not in wallet fragment (probably send monero)
            Timber.d(ex.getLocalizedMessage());
            // keep calm and carry on
        }
        return false;
    }

    @Override
    public void onWalletStored(final boolean success) {
        runOnUiThread(new Runnable() {
            public void run() {
                if (success) {
                    Toast.makeText(WalletActivity.this, getString(R.string.status_wallet_unloaded), Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(WalletActivity.this, getString(R.string.status_wallet_unload_failed), Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    boolean haveWallet = false;

    @Override
    public void onWalletOpen(final Wallet.Device device) {
        switch (device) {
            case Device_Ledger:
                runOnUiThread(new Runnable() {
                    public void run() {
                        showLedgerProgressDialog(LedgerProgressDialog.TYPE_RESTORE);
                    }
                });
        }
    }

    @Override
    public void onWalletStarted(final Wallet.Status walletStatus) {
        runOnUiThread(new Runnable() {
            public void run() {
                dismissProgressDialog();
                if (walletStatus == null) {
                    // guess what went wrong
                    Toast.makeText(WalletActivity.this, getString(R.string.status_wallet_connect_failed), Toast.LENGTH_LONG).show();
                } else {
                    if (Wallet.ConnectionStatus.ConnectionStatus_WrongVersion == walletStatus.getConnectionStatus())
                        Toast.makeText(WalletActivity.this, getString(R.string.status_wallet_connect_wrongversion), Toast.LENGTH_LONG).show();
                    else if (!walletStatus.isOk())
                        Toast.makeText(WalletActivity.this, walletStatus.getErrorString(), Toast.LENGTH_LONG).show();
                }
            }
        });
        if ((walletStatus == null) || (Wallet.ConnectionStatus.ConnectionStatus_Connected != walletStatus.getConnectionStatus())) {
            finish();
        } else {
            haveWallet = true;
            invalidateOptionsMenu();

            enableStreetMode(requestStreetMode);

            final WalletFragment walletFragment = (WalletFragment)
                    getSupportFragmentManager().findFragmentById(R.id.fragment_container);
            runOnUiThread(new Runnable() {
                public void run() {
                    updateAccountsHeader();
                    if (walletFragment != null) {
                        walletFragment.onLoaded();
                    }
                }
            });
        }
    }

    @Override
    public void onTransactionCreated(final String txTag, final PendingTransaction pendingTransaction) {
        try {
            final SendFragment sendFragment = (SendFragment)
                    getSupportFragmentManager().findFragmentById(R.id.fragment_container);
            runOnUiThread(new Runnable() {
                public void run() {
                    dismissProgressDialog();
                    PendingTransaction.Status status = pendingTransaction.getStatus();
                    if (status != PendingTransaction.Status.Status_Ok) {
                        String errorText = pendingTransaction.getErrorString();
                        getWallet().disposePendingTransaction();
                        sendFragment.onCreateTransactionFailed(errorText);
                    } else {
                        sendFragment.onTransactionCreated(txTag, pendingTransaction);
                    }
                }
            });
        } catch (ClassCastException ex) {
            // not in spend fragment
            Timber.d(ex.getLocalizedMessage());
            // don't need the transaction any more
            getWallet().disposePendingTransaction();
        }
    }

    @Override
    public void onSendTransactionFailed(final String error) {
        try {
            final SendFragment sendFragment = (SendFragment)
                    getSupportFragmentManager().findFragmentById(R.id.fragment_container);
            runOnUiThread(new Runnable() {
                public void run() {
                    sendFragment.onSendTransactionFailed(error);
                }
            });
        } catch (ClassCastException ex) {
            // not in spend fragment
            Timber.d(ex.getLocalizedMessage());
        }
    }

    @Override
    public void onTransactionSent(final String txId) {
        try {
            final SendFragment sendFragment = (SendFragment)
                    getSupportFragmentManager().findFragmentById(R.id.fragment_container);
            runOnUiThread(new Runnable() {
                public void run() {
                    sendFragment.onTransactionSent(txId);
                }
            });
        } catch (ClassCastException ex) {
            // not in spend fragment
            Timber.d(ex.getLocalizedMessage());
        }
    }

    @Override
    public void onProgress(final String text) {
        try {
            final WalletFragment walletFragment = (WalletFragment)
                    getSupportFragmentManager().findFragmentByTag(WalletFragment.class.getName());
            runOnUiThread(new Runnable() {
                public void run() {
                    walletFragment.setProgress(text);
                }
            });
        } catch (ClassCastException ex) {
            // not in wallet fragment (probably send monero)
            Timber.d(ex.getLocalizedMessage());
            // keep calm and carry on
        }
    }

    @Override
    public void onProgress(final int n) {
        runOnUiThread(new Runnable() {
            public void run() {
                try {
                    WalletFragment walletFragment = (WalletFragment)
                            getSupportFragmentManager().findFragmentByTag(WalletFragment.class.getName());
                    if (walletFragment != null)
                        walletFragment.setProgress(n);
                } catch (ClassCastException ex) {
                    // not in wallet fragment (probably send monero)
                    Timber.d(ex.getLocalizedMessage());
                    // keep calm and carry on
                }
            }
        });
    }

    private void updateProgress() {
        // TODO maybe show real state of WalletService (like "still closing previous wallet")
        if (hasBoundService()) {
            onProgress(mBoundService.getProgressText());
            onProgress(mBoundService.getProgressValue());
        }
    }

///////////////////////////
// SendFragment.Listener
///////////////////////////

    @Override
    public void onSend(UserNotes notes) {
        if (mIsBound) { // no point in talking to unbound service
            Intent intent = new Intent(getApplicationContext(), WalletService.class);
            intent.putExtra(WalletService.REQUEST, WalletService.REQUEST_CMD_SEND);
            intent.putExtra(WalletService.REQUEST_CMD_SEND_NOTES, notes.txNotes);
            startService(intent);
            Timber.d("SEND TX request sent");
        } else {
            Timber.e("Service not bound");
        }

    }

    @Override
    public void onPrepareSend(final String tag, final TxData txData) {
        if (mIsBound) { // no point in talking to unbound service
            Intent intent = new Intent(getApplicationContext(), WalletService.class);
            intent.putExtra(WalletService.REQUEST, WalletService.REQUEST_CMD_TX);
            intent.putExtra(WalletService.REQUEST_CMD_TX_DATA, txData);
            intent.putExtra(WalletService.REQUEST_CMD_TX_TAG, tag);
            startService(intent);
            Timber.d("CREATE TX request sent");
            if (getWallet().getDeviceType() == Wallet.Device.Device_Ledger)
                showLedgerProgressDialog(LedgerProgressDialog.TYPE_SEND);
        } else {
            Timber.e("Service not bound");
        }
    }

    @Override
    public String getWalletSubaddress(int accountIndex, int subaddressIndex) {
        return getWallet().getSubaddress(accountIndex, subaddressIndex);
    }

    public String getWalletName() {
        return getWallet().getName();
    }

    void popFragmentStack(String name) {
        if (name == null) {
            getSupportFragmentManager().popBackStack();
        } else {
            getSupportFragmentManager().popBackStack(name, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        }
    }

    void replaceFragment(Fragment newFragment, String stackName, Bundle extras) {
        if (extras != null) {
            newFragment.setArguments(extras);
        }
        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        transaction.replace(R.id.fragment_container, newFragment);
        transaction.addToBackStack(stackName);
        transaction.commit();
    }

    private void onWalletDetails() {
        DialogInterface.OnClickListener dialogClickListener = new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                switch (which) {
                    case DialogInterface.BUTTON_POSITIVE:
                        final Bundle extras = new Bundle();
                        extras.putString(GenerateReviewFragment.REQUEST_TYPE, GenerateReviewFragment.VIEW_TYPE_WALLET);

                        if (needVerifyIdentity) {
                            Helper.promptPassword(WalletActivity.this, getWallet().getName(), true, new Helper.PasswordAction() {
                                @Override
                                public void action(String walletName, String password, boolean fingerprintUsed) {
                                    replaceFragment(new GenerateReviewFragment(), null, extras);
                                    needVerifyIdentity = false;
                                }
                            });
                        } else {
                            replaceFragment(new GenerateReviewFragment(), null, extras);
                        }

                        break;
                    case DialogInterface.BUTTON_NEGATIVE:
                        // do nothing
                        break;
                }
            }
        };

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setMessage(getString(R.string.details_alert_message))
                .setPositiveButton(getString(R.string.details_alert_yes), dialogClickListener)
                .setNegativeButton(getString(R.string.details_alert_no), dialogClickListener)
                .show();
    }

    void onShareTxInfo() {
        try {
            TxFragment fragment = (TxFragment)
                    getSupportFragmentManager().findFragmentById(R.id.fragment_container);
            fragment.shareTxInfo();
        } catch (ClassCastException ex) {
            // not in wallet fragment
            Timber.e(ex.getLocalizedMessage());
            // keep calm and carry on
        }
    }

    @Override
    public void onDisposeRequest() {
        //TODO consider doing this through the WalletService to avoid concurrency issues
        getWallet().disposePendingTransaction();
    }

    private boolean startScanFragment = false;

    @Override
    protected void onResumeFragments() {
        super.onResumeFragments();
        if (startScanFragment) {
            startScanFragment();
            startScanFragment = false;
        }
    }

    private void startScanFragment() {
        Bundle extras = new Bundle();
        replaceFragment(new ScannerFragment(), null, extras);
    }

    /// QR scanner callbacks
    @Override
    public void onScan() {
        if (Helper.getCameraPermission(this)) {
            startScanFragment();
        } else {
            Timber.i("Waiting for permissions");
        }

    }

    @Override
    public boolean onScanned(String qrCode) {
        // #gurke
        BarcodeData bcData = BarcodeData.fromQrCode(qrCode);
        if (bcData != null) {
            popFragmentStack(null);
            Timber.d("AAA");
            onUriScanned(bcData);
            return true;
        } else {
            return false;
        }
    }

    OnUriScannedListener onUriScannedListener = null;

    @Override
    public void setOnUriScannedListener(OnUriScannedListener onUriScannedListener) {
        this.onUriScannedListener = onUriScannedListener;
    }

    @Override
    void onUriScanned(BarcodeData barcodeData) {
        super.onUriScanned(barcodeData);
        boolean processed = false;
        if (onUriScannedListener != null) {
            processed = onUriScannedListener.onUriScanned(barcodeData);
        }
        if (!processed || (onUriScannedListener == null)) {
            Toast.makeText(this, getString(R.string.nfc_tag_read_what), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String permissions[],
                                           @NonNull int[] grantResults) {
        Timber.d("onRequestPermissionsResult()");
        switch (requestCode) {
            case Helper.PERMISSIONS_REQUEST_CAMERA:
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    startScanFragment = true;
                } else {
                    String msg = getString(R.string.message_camera_not_permitted);
                    Timber.e(msg);
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                }
                break;
            default:
        }
    }

    @Override
    public void onWalletReceive() {
        startReceive(getWallet().getAddress());
    }

    void startReceive(String address) {
        Timber.d("startReceive()");
        Bundle b = new Bundle();
        b.putString("address", address);
        b.putString("name", getWalletName());
        startReceiveFragment(b);
    }

    void startReceiveFragment(Bundle extras) {
        replaceFragment(new ReceiveFragment(), null, extras);
        Timber.d("ReceiveFragment placed");
    }

    @Override
    public long getTotalFunds() {
        return getWallet().getUnlockedBalance();
    }

    @Override
    public boolean verifyWalletPassword(String password) {
        String walletPassword = Helper.getWalletPassword(getApplicationContext(), getWalletName(), password);
        return walletPassword != null;
    }

    @Override
    public void onBackPressed() {
        if (drawer.isDrawerOpen(GravityCompat.START)) {
            drawer.closeDrawer(GravityCompat.START);
            return;
        }
        final Fragment fragment = getSupportFragmentManager().findFragmentById(R.id.fragment_container);
        if (fragment instanceof OnBackPressedListener) {
            if (!((OnBackPressedListener) fragment).onBackPressed()) {
                super.onBackPressed();
            }
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public void onFragmentDone() {
        popFragmentStack(null);
    }

    @Override
    public SharedPreferences getPrefs() {
        return getPreferences(Context.MODE_PRIVATE);
    }

    private List<Integer> accountIds = new ArrayList<>();

    // generate and cache unique ids for use in accounts list
    private int getAccountId(int accountIndex) {
        final int n = accountIds.size();
        for (int i = n; i <= accountIndex; i++) {
            accountIds.add(View.generateViewId());
        }
        return accountIds.get(accountIndex);
    }

    // drawer stuff

    void updateAccountsBalance() {
        final TextView tvBalance = accountsView.getHeaderView(0).findViewById(R.id.tvBalance);
        if (!isStreetMode()) {
            tvBalance.setText(getString(R.string.accounts_balance,
                    Helper.getDisplayAmount(getWallet().getBalanceAll(), 5)));
        } else {
            tvBalance.setText(null);
        }
        updateAccountsList();
    }

    void updateAccountsHeader() {
        final Wallet wallet = getWallet();
        final TextView tvName = accountsView.getHeaderView(0).findViewById(R.id.tvName);
        tvName.setText(wallet.getName());
    }

    void updateAccountsList() {
        final Wallet wallet = getWallet();
        Menu menu = accountsView.getMenu();
        menu.removeGroup(R.id.accounts_list);
        final int n = wallet.getNumAccounts();
        final boolean showBalances = (n > 1) && !isStreetMode();
        for (int i = 0; i < n; i++) {
            final String label = (showBalances ?
                    getString(R.string.label_account, wallet.getAccountLabel(i), Helper.getDisplayAmount(wallet.getBalance(i), 2))
                    : wallet.getAccountLabel(i));
            final MenuItem item = menu.add(R.id.accounts_list, getAccountId(i), 2 * i, label);
            item.setIcon(R.drawable.ic_account_balance_wallet_black_24dp);
            if (i == wallet.getAccountIndex())
                item.setChecked(true);
        }
        menu.setGroupCheckable(R.id.accounts_list, true, true);
    }

    @Override
    public void setDrawerEnabled(boolean enabled) {
        Timber.d("setDrawerEnabled %b", enabled);
        final int lockMode = enabled ? DrawerLayout.LOCK_MODE_UNLOCKED :
                DrawerLayout.LOCK_MODE_LOCKED_CLOSED;
        drawer.setDrawerLockMode(lockMode);
        drawerToggle.setDrawerIndicatorEnabled(enabled);
        invalidateOptionsMenu(); // menu may need to be changed
    }

    void updateAccountName() {
        setSubtitle(getWallet().getAccountLabel());
        updateAccountsList();
    }

    public void onAccountRename() {
        final LayoutInflater li = LayoutInflater.from(this);
        final View promptsView = li.inflate(R.layout.prompt_rename, null);

        final AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this);
        alertDialogBuilder.setView(promptsView);

        final EditText etRename = promptsView.findViewById(R.id.etRename);
        final TextView tvRenameLabel = promptsView.findViewById(R.id.tvRenameLabel);
        final Wallet wallet = getWallet();
        tvRenameLabel.setText(getString(R.string.prompt_rename, wallet.getAccountLabel()));

        // set dialog message
        alertDialogBuilder
                .setCancelable(false)
                .setPositiveButton(getString(R.string.label_ok),
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int id) {
                                Helper.hideKeyboardAlways(WalletActivity.this);
                                String newName = etRename.getText().toString();
                                wallet.setAccountLabel(newName);
                                updateAccountName();
                            }
                        })
                .setNegativeButton(getString(R.string.label_cancel),
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int id) {
                                Helper.hideKeyboardAlways(WalletActivity.this);
                                dialog.cancel();
                            }
                        });

        final AlertDialog dialog = alertDialogBuilder.create();
        Helper.showKeyboard(dialog);

        // accept keyboard "ok"
        etRename.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if ((event != null && (event.getKeyCode() == KeyEvent.KEYCODE_ENTER) && (event.getAction() == KeyEvent.ACTION_DOWN))
                        || (actionId == EditorInfo.IME_ACTION_DONE)) {
                    Helper.hideKeyboardAlways(WalletActivity.this);
                    String newName = etRename.getText().toString();
                    dialog.cancel();
                    wallet.setAccountLabel(newName);
                    updateAccountName();
                    return false;
                }
                return false;
            }
        });

        dialog.show();
    }

    @Override
    public boolean onNavigationItemSelected(MenuItem item) {
        final int id = item.getItemId();
        switch (id) {
            case R.id.account_new:
                addAccount();
                break;
            default:
                Timber.d("NavigationDrawer ID=%d", id);
                int accountIdx = accountIds.indexOf(id);
                if (accountIdx >= 0) {
                    Timber.d("found @%d", accountIdx);
                    getWallet().setAccountIndex(accountIdx);
                }
                forceUpdate();
                drawer.closeDrawer(GravityCompat.START);
        }
        return true;
    }

    private void addAccount() {
        new AsyncAddAccount().executeOnExecutor(MoneroThreadPoolExecutor.MONERO_THREAD_POOL_EXECUTOR);
    }

    private class AsyncAddAccount extends AsyncTask<Void, Void, Boolean> {
        boolean dialogOpened = false;

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            switch (getWallet().getDeviceType()) {
                case Device_Ledger:
                    showLedgerProgressDialog(LedgerProgressDialog.TYPE_ACCOUNT);
                    dialogOpened = true;
                    break;
                case Device_Software:
                    showProgressDialog(R.string.accounts_progress_new);
                    dialogOpened = true;
                    break;
                default:
                    throw new IllegalStateException("Hardware backing not supported. At all!");
            }
        }

        @Override
        protected Boolean doInBackground(Void... params) {
            if (params.length != 0) return false;
            getWallet().addAccount();
            getWallet().setAccountIndex(getWallet().getNumAccounts() - 1);
            return true;
        }

        @Override
        protected void onPostExecute(Boolean result) {
            super.onPostExecute(result);
            forceUpdate();
            drawer.closeDrawer(GravityCompat.START);
            if (dialogOpened)
                dismissProgressDialog();
            Toast.makeText(WalletActivity.this,
                    getString(R.string.accounts_new, getWallet().getNumAccounts() - 1),
                    Toast.LENGTH_SHORT).show();
        }
    }
}