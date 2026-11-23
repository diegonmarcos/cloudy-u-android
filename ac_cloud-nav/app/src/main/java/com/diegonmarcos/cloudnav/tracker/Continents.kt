package com.diegonmarcos.cloudnav.tracker

import java.text.Normalizer

/**
 * #1863 the World / Cities tables' first column: the continent of a country. The reverse geocoder answers with English or local names
 * (Germany / Deutschland), sometimes an ISO code, so every form below maps to its continent. Unknown = "Other".
 */
object Continents {
    const val EUROPE = "Europe"; const val ASIA = "Asia"; const val AFRICA = "Africa"; const val NORTH_AMERICA = "North America"
    const val SOUTH_AMERICA = "South America"; const val OCEANIA = "Oceania"; const val ANTARCTICA = "Antarctica"; const val OTHER = "Other"

    private val DATA: Map<String, String> = mapOf(
        EUROPE to "al albania shqiperia|ad andorra|am armenia|at austria osterreich|az azerbaijan|by belarus|be belgium belgie belgique belgien|ba bosnia and herzegovina bosna i hercegovina|" +
            "bg bulgaria|hr croatia hrvatska|cy cyprus|cz czechia czech republic cesko ceska republika|dk denmark danmark|ee estonia eesti|fi finland suomi|fr france|ge georgia|" +
            "de germany deutschland|gr greece ellada hellas|hu hungary magyarorszag|is iceland island|ie ireland eire|it italy italia|xk kosovo|lv latvia latvija|li liechtenstein|" +
            "lt lithuania lietuva|lu luxembourg luxemburg|mt malta|md moldova|mc monaco|me montenegro crna gora|nl netherlands nederland the netherlands holland|mk north macedonia macedonia|" +
            "no norway norge|pl poland polska|pt portugal|ro romania|ru russia russian federation rossiya|sm san marino|rs serbia srbija|sk slovakia slovensko|si slovenia slovenija|" +
            "es spain espana|se sweden sverige|ch switzerland schweiz suisse svizzera|ua ukraine ukraina|gb united kingdom uk great britain england scotland wales northern ireland|va vatican city holy see|" +
            "gi gibraltar|fo faroe islands|im isle of man|je jersey|gg guernsey",
        ASIA to "af afghanistan|bh bahrain|bd bangladesh|bt bhutan|bn brunei|kh cambodia|cn china zhongguo|in india bharat|id indonesia|ir iran|iq iraq|il israel|jp japan nippon nihon|" +
            "jo jordan|kz kazakhstan|kw kuwait|kg kyrgyzstan|la laos|lb lebanon|my malaysia|mv maldives|mn mongolia|mm myanmar burma|np nepal|kp north korea|om oman|pk pakistan|" +
            "ps palestine|ph philippines|qa qatar|sa saudi arabia|sg singapore|kr south korea korea republic of korea|lk sri lanka|sy syria|tw taiwan|tj tajikistan|th thailand|" +
            "tl timor-leste east timor|tr turkey turkiye|tm turkmenistan|ae united arab emirates uae|uz uzbekistan|vn vietnam viet nam|ye yemen|hk hong kong|mo macau macao",
        AFRICA to "dz algeria|ao angola|bj benin|bw botswana|bf burkina faso|bi burundi|cv cape verde cabo verde|cm cameroon|cf central african republic|td chad|km comoros|" +
            "cd democratic republic of the congo dr congo|cg republic of the congo congo|ci ivory coast cote d'ivoire|dj djibouti|eg egypt|gq equatorial guinea|er eritrea|sz eswatini swaziland|" +
            "et ethiopia|ga gabon|gm gambia|gh ghana|gn guinea|gw guinea-bissau|ke kenya|ls lesotho|lr liberia|ly libya|mg madagascar|mw malawi|ml mali|mr mauritania|mu mauritius|" +
            "ma morocco maroc|mz mozambique|na namibia|ne niger|ng nigeria|rw rwanda|st sao tome and principe|sn senegal|sc seychelles|sl sierra leone|so somalia|za south africa|" +
            "ss south sudan|sd sudan|tz tanzania|tg togo|tn tunisia|ug uganda|zm zambia|zw zimbabwe|re reunion|yt mayotte",
        NORTH_AMERICA to "ag antigua and barbuda|bs bahamas|bb barbados|bz belize|ca canada|cr costa rica|cu cuba|dm dominica|do dominican republic republica dominicana|sv el salvador|" +
            "gd grenada|gt guatemala|ht haiti|hn honduras|jm jamaica|mx mexico|ni nicaragua|pa panama|kn saint kitts and nevis|lc saint lucia|vc saint vincent and the grenadines|" +
            "tt trinidad and tobago|us united states united states of america usa|pr puerto rico|gl greenland|bm bermuda|aw aruba|cw curacao|gp guadeloupe|mq martinique",
        SOUTH_AMERICA to "ar argentina|bo bolivia|br brazil brasil|cl chile|co colombia|ec ecuador|gy guyana|py paraguay|pe peru|sr suriname|uy uruguay|ve venezuela|gf french guiana|fk falkland islands",
        OCEANIA to "au australia|fj fiji|ki kiribati|mh marshall islands|fm micronesia|nr nauru|nz new zealand aotearoa|pw palau|pg papua new guinea|ws samoa|sb solomon islands|" +
            "to tonga|tv tuvalu|vu vanuatu|nc new caledonia|pf french polynesia|gu guam",
        ANTARCTICA to "aq antarctica",
    )

    /** Every country name (and ISO code) to its continent. A multi-word name is kept whole; single words map too. */
    private val INDEX: Map<String, String> = HashMap<String, String>().also { idx ->
        for ((cont, list) in DATA) for (entry in list.split('|')) {
            val words = entry.trim().split(' ')
            idx[words[0]] = cont                                   // ISO code
            val rest = words.drop(1)
            idx[rest.joinToString(" ")] = cont                     // the whole first name
            // the aliases: every run of words after the code that names the country on its own
            for (i in rest.indices) for (j in i + 1..rest.size) idx.putIfAbsent(rest.subList(i, j).joinToString(" "), cont)
        }
    }

    private fun key(s: String) = Normalizer.normalize(s.trim().lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").replace(Regex("\\s+"), " ")

    fun of(country: String?): String {
        val k = country?.let { key(it) }?.takeIf { it.isNotEmpty() } ?: return OTHER
        return INDEX[k] ?: OTHER
    }
}
