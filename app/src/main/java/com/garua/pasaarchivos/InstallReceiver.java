package com.garua.pasaarchivos;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/** Recibe la respuesta del instalador de Android y muestra la pantalla de confirmación. */
public class InstallReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = android.os.Build.VERSION.SDK_INT >= 33
                    ? i.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class)
                    : legacy(i);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { c.startActivity(confirm); } catch (Exception ignored) {}
            }
        } else if (status != PackageInstaller.STATUS_SUCCESS) {
            String msg = status == PackageInstaller.STATUS_FAILURE_ABORTED ? "Actualización cancelada."
                    : status == PackageInstaller.STATUS_FAILURE_CONFLICT || status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE
                      ? "No se pudo actualizar: la versión descargada no es compatible con la instalada."
                      : "No se pudo instalar la actualización.";
            Toast.makeText(c, msg, Toast.LENGTH_LONG).show();
        }
    }

    @SuppressWarnings("deprecation")
    private static Intent legacy(Intent i) { return i.getParcelableExtra(Intent.EXTRA_INTENT); }
}
