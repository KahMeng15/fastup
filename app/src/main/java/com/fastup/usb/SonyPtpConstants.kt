package com.fastup.usb

object SonyPtpConstants {
    // Sony ILCE-7M3 Hardware Identifiers
    const val SONY_VENDOR_ID = 0x054C      // 1356
    const val SONY_A7M3_PRODUCT_ID = 0x0C34 // 3124

    // USB Imaging Class
    const val USB_CLASS_STILL_IMAGE = 6
    const val USB_SUBCLASS_STILL_IMAGE = 1
    const val USB_PROTOCOL_PTP = 1

    // PTP Container Types
    const val PTP_CONTAINER_UNDEFINED = 0x0000
    const val PTP_CONTAINER_COMMAND = 0x0001
    const val PTP_CONTAINER_DATA = 0x0002
    const val PTP_CONTAINER_RESPONSE = 0x0003
    const val PTP_CONTAINER_EVENT = 0x0004

    // PTP Operation Codes (Standard + Vendor)
    const val PTP_OP_UNDEFINED = 0x1000
    const val PTP_OP_GET_DEVICE_INFO = 0x1001
    const val PTP_OP_OPEN_SESSION = 0x1002
    const val PTP_OP_CLOSE_SESSION = 0x1003
    const val PTP_OP_GET_STORAGE_IDS = 0x1004
    const val PTP_OP_GET_STORAGE_INFO = 0x1005
    const val PTP_OP_GET_NUM_OBJECTS = 0x1006
    const val PTP_OP_GET_OBJECT_HANDLES = 0x1007
    const val PTP_OP_GET_OBJECT_INFO = 0x1008
    const val PTP_OP_GET_OBJECT = 0x1009
    const val PTP_OP_GET_THUMB = 0x100A
    const val PTP_OP_DELETE_OBJECT = 0x100B

    const val PTP_OP_GET_DEVICE_PROP_VALUE = 0x1015
    const val SONY_DPC_OBJECT_IN_MEMORY = 0xD215

    // Sony Vendor Specific PTP Opcodes
    const val SONY_OP_SDIO_CONNECT = 0x9201
    const val SONY_OP_SDIO_GET_EXT_DEVICE_INFO = 0x9202
    const val SONY_OP_CAPTURE = 0x9207

    // PTP Response Codes
    const val PTP_RESP_OK = 0x2001
    const val PTP_RESP_GENERAL_ERROR = 0x2002
    const val PTP_RESP_SESSION_NOT_OPEN = 0x2003
    const val PTP_RESP_INVALID_TRANSACTION_ID = 0x2004
    const val PTP_RESP_SESSION_ALREADY_OPEN = 0x201E

    // PTP Event Codes
    const val PTP_EVENT_OBJECT_ADDED = 0x4002
    const val PTP_EVENT_OBJECT_REMOVED = 0x4003
    const val PTP_EVENT_STORE_ADDED = 0x4004
    const val PTP_EVENT_STORE_REMOVED = 0x4005
    const val PTP_EVENT_DEVICE_PROP_CHANGED = 0x4006
    const val PTP_EVENT_OBJECT_INFO_CHANGED = 0x4007
    const val PTP_EVENT_CAPTURE_COMPLETE = 0x400D
    const val SONY_EVENT_OBJECT_READY = 0xC201
    const val PHOTO_OBJECT_HANDLE = 0xFFFFC001.toInt()
    const val LIVEVIEW_OBJECT_HANDLE = 0xFFFFC002.toInt()
}
