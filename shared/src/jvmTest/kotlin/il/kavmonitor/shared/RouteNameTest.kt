package il.kavmonitor.shared

import il.kavmonitor.shared.model.RouteEnd
import il.kavmonitor.shared.model.RouteName
import kotlin.test.Test
import kotlin.test.assertEquals

/** Real `route_long_name` values from Stride gtfs_routes (2026-10-06). */
class RouteNameTest {
    private fun check(long: String, dir: String, alt: String, origin: RouteEnd, dest: RouteEnd) {
        val p = RouteName.parse(long, dir, alt)
        assertEquals(origin, p.origin, "origin of $long")
        assertEquals(dest, p.destination, "destination of $long")
    }

    @Test fun line194BothDirections() {
        check("ת. רכבת תל אביב- סבידור/רציפים A-תל אביב יפו<->ת. רכבת הראשונים-ראשון לציון-1#", "1", "#",
            RouteEnd("ת. רכבת תל אביב- סבידור/רציפים A", "תל אביב יפו"), RouteEnd("ת. רכבת הראשונים", "ראשון לציון"))
        check("ת. רכבת הראשונים-ראשון לציון<->ת. רכבת תל אביב - סבידור/דרך נמיר-תל אביב יפו-2#", "2", "#",
            RouteEnd("ת. רכבת הראשונים", "ראשון לציון"), RouteEnd("ת. רכבת תל אביב - סבידור/דרך נמיר", "תל אביב יפו"))
    }

    @Test fun numericAlternativeAndHyphenInStopName() {
        check("חניון רכבת מלחה/דרך יצחק מודעי-ירושלים<->הזית ב-מטע-10", "1", "0",
            RouteEnd("חניון רכבת מלחה/דרך יצחק מודעי", "ירושלים"), RouteEnd("הזית ב", "מטע"))
        check("יפיע/אל-אלמאניה-יפיע<->מרכז מסחרי/המעיין-נוף הגליל-2#", "2", "#",
            RouteEnd("יפיע/אל-אלמאניה", "יפיע"), RouteEnd("מרכז מסחרי/המעיין", "נוף הגליל"))
    }

    @Test fun hebrewLetterAlternativeWithoutDirectionInfo() {
        // direction/alternative unknown → generic "-<digit><code>" suffix removal
        check("ממילא/קריב-ירושלים<->מסוף רדינג/הורדה-תל אביב יפו-3ק", "", "",
            RouteEnd("ממילא/קריב", "ירושלים"), RouteEnd("מסוף רדינג/הורדה", "תל אביב יפו"))
    }

    @Test fun cityWithArabicArticleHyphen() {
        check("מסוף/כניסה-אום אל-פחם<->צומת מי עמי-עין עירון-1#", "1", "#",
            RouteEnd("מסוף/כניסה", "אום אל-פחם"), RouteEnd("צומת מי עמי", "עין עירון"))
    }

    @Test fun trailingSpacesAndMissingDestination() {
        check("ת.רכבת חדרה מערב/רציפים    -חדרה<->אדני פז/הדודאים-חדרה-15", "1", "5",
            RouteEnd("ת.רכבת חדרה מערב/רציפים", "חדרה"), RouteEnd("אדני פז/הדודאים", "חדרה"))
        val p = RouteName.parse("משהו בלי חץ")
        assertEquals(RouteEnd("משהו בלי חץ", null), p.origin)
        assertEquals("", p.destination.stop)
    }

    @Test
    fun escapedGershayimAreNormalized() {
        val n = RouteName.parse("הזית ב-מטע<->גן טכנולוגי/א''''ס הפועל-ירושלים-20", "2", "0")
        assertEquals(RouteEnd("גן טכנולוגי/א\"ס הפועל", "ירושלים"), n.destination)
        assertEquals("בועיינה-נוג'ידאת", RouteName.cleanName("בועיינה-נוג'ידאת"))
        assertEquals("צומת חב\"ד", RouteName.cleanName("צומת חב''ד"))
    }
}
