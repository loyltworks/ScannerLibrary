package com.loyltworks.lwsscanner

import android.Manifest
import android.os.Bundle
import android.os.Handler
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.loyltworks.mylibrary.qrcodescanner.QrCodeScannerListner
import com.loyltworks.mylibrary.qrcodescanner.QrCodeScanner
import com.permissionx.guolindev.PermissionX

class MainActivity : AppCompatActivity(), QrCodeScannerListner {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        var preview=findViewById<PreviewView>(R.id.scannerPreview)

        PermissionX.init(this)
            .permissions(
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
            .explainReasonBeforeRequest()
            .onExplainRequestReason { scope, deniedList ->
                scope.showRequestReasonDialog(
                    deniedList,
                    "Core fundamental are based on these permissions",
                    "OK",
                    "Cancel"
                )
            }
            .onForwardToSettings { scope, deniedList ->
                scope.showForwardToSettingsDialog(
                    deniedList,
                    "You need to allow necessary permissions in Settings manually",
                    "OK",
                    "Cancel"
                )
            }
            .request { allGranted, grantedList, deniedList ->
                if (allGranted) {
                    // All permissions granted so start scanner
                    QrCodeScanner.startScanner(this,preview,this)
                } else {
                    // Finish activity on permission denied
                    this.finish()
                }
            }




    }

    override fun onSuccess(qrCode: String) {

    }

    override fun onFailed(reason: String) {

    }



}

