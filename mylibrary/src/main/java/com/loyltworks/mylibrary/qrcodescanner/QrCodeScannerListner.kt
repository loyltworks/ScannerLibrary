package com.loyaltyworks.fleetguardhumsafar.utils.qrcodescanner

interface QrCodeScannerListner {

    fun onSuccess(qrCode:String)
    fun onFailed(reason:String)
}