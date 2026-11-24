package org.thor2.subscreen;

/** Время словами: «без пяти четыре», «половина третьего», «ten past four». */
final class WordClock {
    private WordClock() {
    }

    /** Возвращает {фраза, слово-час} — слово-час подсвечивается акцентным цветом. */
    static String[] phrase(int hour24, int minute, boolean russian) {
        return russian ? ru(hour24, minute) : en(hour24, minute);
    }

    // ---- русский ----

    private static final String[] HOUR_NOM = {"двенадцать", "час", "два", "три", "четыре", "пять",
            "шесть", "семь", "восемь", "девять", "десять", "одиннадцать", "двенадцать"};
    private static final String[] HOUR_GEN_ORD = {"двенадцатого", "первого", "второго", "третьего",
            "четвёртого", "пятого", "шестого", "седьмого", "восьмого", "девятого", "десятого",
            "одиннадцатого", "двенадцатого"};
    private static final String[] UNITS_F = {"", "одна", "две", "три", "четыре", "пять", "шесть", "семь",
            "восемь", "девять", "десять", "одиннадцать", "двенадцать", "тринадцать", "четырнадцать",
            "пятнадцать", "шестнадцать", "семнадцать", "восемнадцать", "девятнадцать"};
    private static final String[] UNITS_GEN = {"", "одной", "двух", "трёх", "четырёх", "пяти", "шести",
            "семи", "восьми", "девяти", "десяти", "одиннадцати", "двенадцати", "тринадцати",
            "четырнадцати", "пятнадцати", "шестнадцати", "семнадцати", "восемнадцати", "девятнадцати"};

    private static String[] ru(int h, int m) {
        if (m == 0 && (h == 0 || h == 12)) {
            String w = h == 0 ? "полночь" : "полдень";
            return new String[] {w, w};
        }
        int cur = h % 12;
        int next = (cur + 1) % 12;
        if (m == 0) {
            String w = HOUR_NOM[cur == 0 ? 12 : cur];
            return new String[] {"ровно " + w, w};
        }
        if (m == 30) {
            String w = HOUR_GEN_ORD[next];
            return new String[] {"половина " + w, w};
        }
        if (m == 15) {
            String w = HOUR_GEN_ORD[next];
            return new String[] {"четверть " + w, w};
        }
        if (m == 45) {
            String w = HOUR_NOM[next == 0 ? 12 : next];
            return new String[] {"без четверти " + w, w};
        }
        int left = 60 - m;
        if (m > 30 && (left <= 20 || left % 5 == 0)) {
            String w = HOUR_NOM[next == 0 ? 12 : next];
            return new String[] {"без " + genitive(left) + " " + w, w};
        }
        String w = HOUR_GEN_ORD[next];
        return new String[] {feminine(m) + " " + minutesWord(m) + " " + w, w};
    }

    private static String feminine(int n) {
        if (n < 20) {
            return UNITS_F[n];
        }
        String tens = n / 10 == 2 ? "двадцать" : n / 10 == 3 ? "тридцать" : n / 10 == 4 ? "сорок" : "пятьдесят";
        return n % 10 == 0 ? tens : tens + " " + UNITS_F[n % 10];
    }

    private static String genitive(int n) {
        if (n < 20) {
            return UNITS_GEN[n];
        }
        String tens = n / 10 == 2 ? "двадцати" : "тридцати";
        return n % 10 == 0 ? tens : tens + " " + UNITS_GEN[n % 10];
    }

    private static String minutesWord(int n) {
        int d = n % 10;
        if (n % 100 >= 11 && n % 100 <= 14) {
            return "минут";
        }
        return d == 1 ? "минута" : d >= 2 && d <= 4 ? "минуты" : "минут";
    }

    // ---- english ----

    private static final String[] EN = {"twelve", "one", "two", "three", "four", "five", "six", "seven",
            "eight", "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "quarter", "sixteen",
            "seventeen", "eighteen", "nineteen", "twenty"};

    private static String[] en(int h, int m) {
        int cur = h % 12;
        int next = (cur + 1) % 12;
        if (m == 0) {
            return new String[] {"it's " + EN[cur] + " o'clock", EN[cur]};
        }
        if (m == 30) {
            return new String[] {"half past " + EN[cur], EN[cur]};
        }
        if (m < 30) {
            return new String[] {number(m) + " past " + EN[cur], EN[cur]};
        }
        return new String[] {number(60 - m) + " to " + EN[next], EN[next]};
    }

    private static String number(int n) {
        if (n <= 20) {
            return EN[n];
        }
        return "twenty-" + EN[n - 20];
    }
}
