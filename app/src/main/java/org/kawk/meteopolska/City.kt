package org.kawk.meteopolska

data class City(val name: String, val col: Int, val row: Int)

object Cities {
    val ALL: List<City> = listOf(
        City("Białystok", 285, 379),
        City("Bydgoszcz", 199, 381),
        City("Gdańsk", 210, 346),
        City("Gorzów Wielkopolski", 152, 390),
        City("Katowice", 215, 461),
        City("Kielce", 244, 443),
        City("Kraków", 232, 466),
        City("Lublin", 277, 432),
        City("Łódź", 223, 418),
        City("Olsztyn", 240, 363),
        City("Opole", 196, 449),
        City("Poznań", 180, 400),
        City("Rzeszów", 269, 465),
        City("Szczecin", 142, 370),
        City("Toruń", 209, 383),
        City("Warszawa", 250, 406),
        City("Wrocław", 181, 436),
        City("Zielona Góra", 155, 412),
    )

    val DEFAULT: City = ALL.first { it.name == "Toruń" }

    fun byName(name: String): City? = ALL.firstOrNull { it.name == name }

    fun names(): List<String> = ALL.map { it.name }
}
