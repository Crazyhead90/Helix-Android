package io.helix.android.ui.wallet_activity;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import org.helixj.core.Coin;
import org.helixj.core.InsufficientMoneyException;
import org.helixj.core.Transaction;
import org.helixj.uri.helixURI;
import org.helixj.wallet.Wallet;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import io.helix.android.helixApplication;
import io.helix.android.R;
import io.helix.android.module.CantSweepBalanceException;
import io.helix.android.module.NoPeerConnectedException;
import io.helix.android.rate.db.helixRate;
import io.helix.android.service.IntentsConstants;
import io.helix.android.ui.base.BaseDrawerActivity;
import io.helix.android.ui.base.dialogs.DialogListener;
import io.helix.android.ui.base.dialogs.SimpleTextDialog;
import io.helix.android.ui.base.dialogs.SimpleTwoButtonsDialog;
import io.helix.android.ui.qr_activity.QrActivity;
import io.helix.android.ui.settings_backup_activity.SettingsBackupActivity;
import io.helix.android.ui.transaction_send_activity.SendActivity;
import io.helix.android.ui.upgrade.UpgradeWalletActivity;
import io.helix.android.utils.DialogsUtil;
import io.helix.android.utils.scanner.ScanActivity;

import static android.Manifest.permission.CAMERA;
import static io.helix.android.service.IntentsConstants.ACTION_NOTIFICATION;
import static io.helix.android.service.IntentsConstants.INTENT_BROADCAST_DATA_ON_COIN_RECEIVED;
import static io.helix.android.service.IntentsConstants.INTENT_BROADCAST_DATA_TYPE;
import static io.helix.android.utils.scanner.ScanActivity.INTENT_EXTRA_RESULT;

/**
 * Created by Neoperol on 5/11/17.
 */

public class WalletActivity extends BaseDrawerActivity {

    private static final int SCANNER_RESULT = 122;

    private View root;
    private View container_txs;
    private FloatingActionButton fab_add;

    private TextView txt_value;
    private TextView txt_unnavailable;
    private TextView txt_local_currency;
    private TextView txt_watch_only;
    private helixRate helixRate;
    private TransactionsFragmentBase txsFragment;

    // Receiver
    private LocalBroadcastManager localBroadcastManager;

    private IntentFilter helixServiceFilter = new IntentFilter(ACTION_NOTIFICATION);
    private BroadcastReceiver helixServiceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(ACTION_NOTIFICATION)){
                if(intent.getStringExtra(INTENT_BROADCAST_DATA_TYPE).equals(INTENT_BROADCAST_DATA_ON_COIN_RECEIVED)){
                    updateBalance();
                    txsFragment.refresh();
                }
            }

        }
    };

    @Override
    protected void beforeCreate(){
        /*
        if (!appConf.isAppInit()){
            Intent intent = new Intent(this, SplashActivity.class);
            startActivity(intent);
            finish();
        }
        // show report dialog if something happen with the previous process
        */
        localBroadcastManager = LocalBroadcastManager.getInstance(this);
    }

    @Override
    protected void onCreateView(Bundle savedInstanceState, ViewGroup container) {
        setTitle(R.string.my_wallet);
        root = getLayoutInflater().inflate(R.layout.fragment_wallet, container);
        View containerHeader = getLayoutInflater().inflate(R.layout.fragment_amount,header_container);
        header_container.setVisibility(View.VISIBLE);
        txt_value = (TextView) containerHeader.findViewById(R.id.phrValue);
        txt_unnavailable = (TextView) containerHeader.findViewById(R.id.txt_unnavailable);
        container_txs = root.findViewById(R.id.container_txs);
        txt_local_currency = (TextView) containerHeader.findViewById(R.id.txt_local_currency);
        txt_watch_only = (TextView) containerHeader.findViewById(R.id.txt_watch_only);

        // Open Send
        fab_add = (FloatingActionButton) root.findViewById(R.id.fab_add);
        fab_add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (helixModule.isWalletWatchOnly()){
                    Toast.makeText(v.getContext(),R.string.error_watch_only_mode,Toast.LENGTH_SHORT).show();
                    return;
                }
                startActivity(new Intent(v.getContext(), SendActivity.class));
            }
        });

        txsFragment = (TransactionsFragmentBase) getSupportFragmentManager().findFragmentById(R.id.transactions_fragment);

    }

    @Override
    protected void onResume() {
        super.onResume();
        // to check current activity in the navigation drawer
        setNavigationMenuItemChecked(0);

        init();

        // register
        localBroadcastManager.registerReceiver(helixServiceReceiver,helixServiceFilter);

        updateState();
        updateBalance();

        // check if this wallet need an update:
        try {
            if(helixModule.isBip32Wallet() && helixModule.isSyncWithNode()){
                if (!helixModule.isWalletWatchOnly() && helixModule.getAvailableBalanceCoin().isGreaterThan(Transaction.DEFAULT_TX_FEE)) {
                    Intent intent = UpgradeWalletActivity.createStartIntent(
                            this,
                            getString(R.string.upgrade_wallet),
                            "An old wallet version with bip32 key was detected, in order to upgrade the wallet your coins are going to be sweeped" +
                                    " to a new wallet with bip44 account.\n\nThis means that your current mnemonic code and" +
                                    " backup file are not going to be valid anymore, please write the mnemonic code in paper " +
                                    "or export the backup file again to be able to backup your coins." +
                                    "\n\nPlease wait and not close this screen. The upgrade + blockchain sychronization could take a while."
                                    +"\n\nTip: If this screen is closed for user's mistake before the upgrade is finished you can find two backups files in the 'Download' folder" +
                                    " with prefix 'old' and 'upgrade' to be able to continue the restore manually."
                                    + "\n\nThanks!",
                            "sweepBip32"
                    );
                    startActivity(intent);
                }
            }
        } catch (NoPeerConnectedException e) {
            e.printStackTrace();
        }
    }

    private void updateState() {
        txt_watch_only.setVisibility(helixModule.isWalletWatchOnly()?View.VISIBLE:View.GONE);
    }

    private void init() {
        // Start service if it's not started.
        helixApplication.starthelixService();

        if (!helixApplication.getAppConf().hasBackup()){
            long now = System.currentTimeMillis();
            if (helixApplication.getLastTimeRequestedBackup()+1800000L<now) {
                helixApplication.setLastTimeBackupRequested(now);
                SimpleTwoButtonsDialog reminderDialog = DialogsUtil.buildSimpleTwoBtnsDialog(
                        this,
                        getString(R.string.reminder_backup),
                        getString(R.string.reminder_backup_body),
                        new SimpleTwoButtonsDialog.SimpleTwoBtnsDialogListener() {
                            @Override
                            public void onRightBtnClicked(SimpleTwoButtonsDialog dialog) {
                                startActivity(new Intent(WalletActivity.this, SettingsBackupActivity.class));
                                dialog.dismiss();
                            }

                            @Override
                            public void onLeftBtnClicked(SimpleTwoButtonsDialog dialog) {
                                dialog.dismiss();
                            }
                        }
                );
                reminderDialog.setLeftBtnText(getString(R.string.button_dismiss));
                reminderDialog.setLeftBtnTextColor(Color.BLACK);
                reminderDialog.setRightBtnText(getString(R.string.button_ok));
                reminderDialog.show();
            }
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // unregister
        //localBroadcastManager.unregisterReceiver(localReceiver);
        localBroadcastManager.unregisterReceiver(helixServiceReceiver);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId()==R.id.action_qr){
            startActivity(new Intent(this, QrActivity.class));
            return true;
        }else if (item.getItemId()==R.id.action_scan){
            if (!checkPermission(CAMERA)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    int permsRequestCode = 200;
                    String[] perms = {"android.permission.CAMERA"};
                    requestPermissions(perms, permsRequestCode);
                }
            }
            startActivityForResult(new Intent(this, ScanActivity.class),SCANNER_RESULT);
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == SCANNER_RESULT){
            if (resultCode==RESULT_OK) {
                try {
                    String address = data.getStringExtra(INTENT_EXTRA_RESULT);
                    String usedAddress;
                    if (helixModule.chechAddress(address)){
                        usedAddress = address;
                    }else {
                        helixURI helixUri = new helixURI(address);
                        usedAddress = helixUri.getAddress().toBase58();
                    }
                    DialogsUtil.showCreateAddressLabelDialog(this,usedAddress);
                }catch (Exception e){
                    e.printStackTrace();
                    Toast.makeText(this,"Bad address",Toast.LENGTH_LONG).show();
                }
            }
        }
        super.onActivityResult(requestCode, resultCode, data);
    }



    private boolean checkPermission(String permission) {
        int result = ContextCompat.checkSelfPermission(getApplicationContext(),permission);

        return result == PackageManager.PERMISSION_GRANTED;
    }


    private void updateBalance() {
        Coin availableBalance = helixModule.getAvailableBalanceCoin();
        txt_value.setText(
            !availableBalance.isZero()
                ? availableBalance.toFriendlyString()
                : String.format("0 %1$s", getString(R.string.wallet_phr)));
        Coin unnavailableBalance = helixModule.getUnnavailableBalanceCoin();
        txt_unnavailable.setText(
            !unnavailableBalance.isZero()
                ? unnavailableBalance.toFriendlyString()
                : String.format("0 %1$s", getString(R.string.wallet_phr)));
        if (helixRate == null)
            helixRate = helixModule.getRate(helixApplication.getAppConf().getSelectedRateCoin());
        if (helixRate!=null) {
            txt_local_currency.setText(
                    helixApplication.getCentralFormats().format(
                            new BigDecimal(availableBalance.getValue() * helixRate.getRate().doubleValue()).movePointLeft(8)
                    )
                    + " "+helixRate.getCode()
            );
        }else {
            if (helixRate != null) {
                txt_local_currency.setText("0 " + helixRate.getCode());
            } else {
                txt_local_currency.setText("");
            }
        }
    }
}
