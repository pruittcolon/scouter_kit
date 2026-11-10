package scouterkit.ssh

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract

// Calls and texts (Alt+J): the phone's starred contacts (its Favorites), called or texted from the glasses. Needs
// Contacts, Phone and SMS allowed for Vox (Settings, Calls and texts). Texts go through the phone's own SMS.
object People {
    class Person(val name: String, val number: String)

    fun allowed(c: Context) = listOf(android.Manifest.permission.READ_CONTACTS, android.Manifest.permission.CALL_PHONE,
        android.Manifest.permission.SEND_SMS).all { c.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    // The starred contacts with a phone number (each once, its main number first), at most 9.
    fun favorites(c: Context): List<Person> {
        if (c.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val out = linkedMapOf<String, Person>()
        runCatching {
            c.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY),
                "${ContactsContract.CommonDataKinds.Phone.STARRED} = 1", null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC, ${ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY} DESC")
                ?.use { cur ->
                    while (cur.moveToNext()) {
                        val id = cur.getString(0) ?: continue
                        val number = cur.getString(2)?.trim().orEmpty()
                        if (number.isEmpty() || id in out) continue
                        out[id] = Person(cur.getString(1) ?: number, number)
                    }
                }
        }
        return out.values.take(9)
    }

    // A call now (Phone allowed), else the dialer with the number. "" or why not.
    fun call(c: Context, number: String): String = runCatching {
        val can = c.checkSelfPermission(android.Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        c.startActivity(Intent(if (can) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ""
    }.getOrElse { "the call did not start (${it.message})" }

    // A text through the phone's SMS (long ones in parts). "" or why not.
    fun text(c: Context, number: String, message: String): String {
        if (c.checkSelfPermission(android.Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED)
            return "allow SMS for Vox first (Settings, Calls and texts)"
        return runCatching {
            val sms = c.getSystemService(android.telephony.SmsManager::class.java)
            val parts = sms.divideMessage(message)
            if (parts.size > 1) sms.sendMultipartTextMessage(number, null, parts, null, null)
            else sms.sendTextMessage(number, null, message, null, null)
            ""
        }.getOrElse { "the text did not go (${it.message})" }
    }
}
