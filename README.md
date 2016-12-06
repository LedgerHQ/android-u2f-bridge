# Android U2F Bridge

This application is an early implementation of an U2F implementation for USB devices on Android. Expect alpha quality software : the UI is buggy, the U2F implementation has been thoroughly simplified, and it works sometimes, for many devices, and sometimes not (typically on a Yubikey v4, for unknown reasons). It is mostly intended for developers, U2F fans or Ethereum enthusiasts who want to join the latest popular ICO with MyEtherWallet on Android.

The bridge aggressively catches events sent to Google Authenticator - if you wish to test it with a different event source, comment it out in the manifest.  

Building
========

Use gradlew assembleDebug

Contact
=======

Please report bugs and features to hello@ledger.fr

