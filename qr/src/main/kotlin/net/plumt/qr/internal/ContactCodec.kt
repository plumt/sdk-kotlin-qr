package net.plumt.qr.internal

import net.plumt.qr.ContactPhone
import net.plumt.qr.PhoneType
import net.plumt.qr.QrPayload

/** 연락처 ↔ vCard·MECARD. */
internal object ContactCodec {

    private val VCARD_BOUNDARY = ContentLines.Boundary(
        setOf("BEGIN", "VERSION", "FN", "N", "TEL", "EMAIL", "ORG", "TITLE", "ADR", "URL", "NOTE", "END"),
        allowGroup = true,
    )

    fun encodeVCard(contact: QrPayload.Contact): String {
        validate(contact)
        val c = contact.trimmed()
        return buildList {
            add("BEGIN:VCARD")
            add("VERSION:3.0")
            // 3.0 은 N·FN 을 둘 다 요구한다. 이름을 나눠 받지 않으므로 N 의 성 자리에 통째로 둔다
            add("N:${TextCodec.escapeText(c.name)};;;;")
            add("FN:${TextCodec.escapeText(c.name)}")
            if (c.organization.isNotEmpty()) add("ORG:${TextCodec.escapeText(c.organization)}")
            if (c.title.isNotEmpty()) add("TITLE:${TextCodec.escapeText(c.title)}")
            c.phones.forEach { phone ->
                val type = vCardType(phone.type)
                add("TEL" + (if (type == null) "" else ";TYPE=$type") + ":" + TextCodec.escapeText(phone.number))
            }
            c.emails.forEach { add("EMAIL:${TextCodec.escapeText(it)}") }
            if (c.address.isNotEmpty()) add("ADR:;;${TextCodec.escapeText(c.address)};;;;")
            if (c.url.isNotEmpty()) add("URL:${c.url}")
            if (c.note.isNotEmpty()) add("NOTE:${TextCodec.escapeText(c.note)}")
            add("END:VCARD")
        }.joinToString("\n")
    }

    fun encodeMeCard(contact: QrPayload.Contact): String {
        validate(contact)
        val c = contact.trimmed()
        val esc = { value: String -> TextCodec.escapeBackslash(value, TextCodec.MECARD_SPECIALS) }
        return buildString {
            append("MECARD:")
            append("N:").append(TextCodec.escapeBackslash(c.name, TextCodec.MECARD_SPECIALS + ",")).append(';')
            if (c.organization.isNotEmpty()) append("ORG:").append(esc(c.organization)).append(';')
            c.phones.forEach { append("TEL:").append(esc(it.number)).append(';') }
            c.emails.forEach { append("EMAIL:").append(esc(it)).append(';') }
            if (c.address.isNotEmpty()) append("ADR:").append(esc(c.address)).append(';')
            if (c.url.isNotEmpty()) append("URL:").append(esc(c.url)).append(';')
            if (c.note.isNotEmpty()) append("NOTE:").append(esc(c.note)).append(';')
            append(';')
        }
    }

    fun parseVCard(content: String): QrPayload.Contact? {
        val lines = ContentLines.parse(content, VCARD_BOUNDARY).filterNot { it.isBinary }
        if (lines.none { it.name == "BEGIN" && it.rawValue.trim().equals("VCARD", ignoreCase = true) }) return null
        val first = { name: String -> lines.firstOrNull { it.name == name } }
        val structuredName = first("N")?.components()?.filter(String::isNotEmpty)?.reversed()?.joinToString(" ").orEmpty()
        return QrPayload.Contact(
            name = first("FN")?.text().orEmpty().ifEmpty { structuredName },
            organization = first("ORG")?.components()?.filter(String::isNotEmpty)?.joinToString(" ").orEmpty(),
            title = first("TITLE")?.text().orEmpty(),
            phones = lines.filter { it.name == "TEL" }.mapNotNull { line ->
                val number = line.text().let { if (it.startsWith("tel:", ignoreCase = true)) it.substring(4) else it }.trim()
                number.takeIf(String::isNotEmpty)?.let { ContactPhone(it, phoneType(line.types)) }
            },
            emails = lines.filter { it.name == "EMAIL" }.map { it.text() }.filter(String::isNotEmpty),
            address = first("ADR")?.components()?.filter(String::isNotEmpty)?.joinToString(", ").orEmpty(),
            url = first("URL")?.text().orEmpty(),
            note = first("NOTE")?.text().orEmpty(),
        )
    }

    /** 일본 휴대폰 형식 `MECARD:N:이름;TEL:번호;EMAIL:주소;;`. 이스케이프는 역슬래시형이다. */
    fun parseMeCard(content: String): QrPayload.Contact {
        // vCard 로 다시 쓰면 줄바꿈이 \n 하나로 맞춰지므로 읽을 때 미리 맞춘다 — 두 형식의 왕복 값이 같아지게
        val fields = TextCodec.prefixedFields(content.substring("MECARD:".length))
        val all = { key: String -> fields.filter { it.first == key }.map { normalizeLines(TextCodec.unescapeBackslash(it.second)).trim() } }
        val name = fields.firstOrNull { it.first == "N" }?.second
            ?.let { raw -> TextCodec.splitUnescaped(raw, ',').map { normalizeLines(TextCodec.unescapeBackslash(it)).trim() } }
            ?.filter(String::isNotEmpty)?.joinToString(" ").orEmpty()
        return QrPayload.Contact(
            name = name,
            organization = all("ORG").firstOrNull().orEmpty(),
            phones = all("TEL").filter(String::isNotEmpty).map { ContactPhone(it) },
            emails = all("EMAIL").filter(String::isNotEmpty),
            address = all("ADR").firstOrNull().orEmpty(),
            url = all("URL").firstOrNull().orEmpty(),
            note = all("NOTE").firstOrNull().orEmpty(),
        )
    }

    private fun normalizeLines(value: String) = value.replace("\r\n", "\n").replace('\r', '\n')

    private fun validate(contact: QrPayload.Contact) {
        require(
            contact.name.isNotBlank() || contact.organization.isNotBlank() || contact.phones.isNotEmpty() || contact.emails.isNotEmpty(),
        ) { "name·organization·phones·emails 중 하나는 있어야 한다" }
        contact.phones.forEachIndexed { i, phone -> require(phone.number.isNotBlank()) { "phones[$i].number 가 비어 있다" } }
        contact.emails.forEachIndexed { i, email -> require(email.isNotBlank()) { "emails[$i] 가 비어 있다" } }
        require(contact.url.none { it == '\n' || it == '\r' }) { "url 에 줄바꿈이 있다" }
    }

    private fun QrPayload.Contact.trimmed() = copy(
        name = name.trim(),
        organization = organization.trim(),
        title = title.trim(),
        phones = phones.map { it.copy(number = it.number.trim()) },
        emails = emails.map(String::trim),
        address = address.trim(),
        url = url.trim(),
        note = note.trim(),
    )

    private fun vCardType(type: PhoneType): String? = when (type) {
        PhoneType.MOBILE -> "CELL"
        PhoneType.HOME -> "HOME"
        PhoneType.WORK -> "WORK"
        PhoneType.FAX -> "FAX"
        PhoneType.OTHER -> null
    }

    private fun phoneType(types: Set<String>): PhoneType = when {
        "FAX" in types -> PhoneType.FAX
        "CELL" in types || "MOBILE" in types || "IPHONE" in types -> PhoneType.MOBILE
        "WORK" in types -> PhoneType.WORK
        "HOME" in types -> PhoneType.HOME
        else -> PhoneType.OTHER
    }
}
