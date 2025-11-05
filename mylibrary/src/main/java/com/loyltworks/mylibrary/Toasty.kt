package com.loyltworks.mylibrary

import android.content.Context
import android.widget.Toast

object Toasty {

    fun showToast(context: Context){
        Toast.makeText(context,"Success",Toast.LENGTH_LONG).show()
    }
}