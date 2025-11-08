package com.loyltworks.mylibrary.qrcodescanner

interface QrCodeScannerListner {

    fun onSuccess(qrCode:String)
    fun onFailed(reason:String)
}