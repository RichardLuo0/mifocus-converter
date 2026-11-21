# MiFocus Converter
Created with deepseek V4.1 Flash 1017.

It just works™.

## Features
1. Convert mi focus notification into aosp live update notification.
2. Extract sms code from message notification and add it as an action button.

## Installation
1. Install the apk.
2. Select system in lsposed.
3. Use a device spoof module such as https://github.com/Seyud/device_faker to spoof as Mi device. Make sure you have at least these properties:
    ```ini
    ro.miui.ui.version.code=11
    ro.miui.ui.version.name=V125
    ro.mi.os.version.code=4
    persist.sys.feature.island=true
    ```
4. That's it.
