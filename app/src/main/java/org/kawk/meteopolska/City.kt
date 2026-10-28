package org.kawk.meteopolska

data class City(val name: String, val lat: Double, val lon: Double)

object Cities {
    val ALL: List<City> = listOf(
        City("Białystok", 53.1325, 23.1688),
        City("Bydgoszcz", 53.1235, 18.0084),
        City("Gdańsk", 54.3520, 18.6466),
        City("Gorzów Wielkopolski", 52.7368, 15.2288),
        City("Katowice", 50.2649, 19.0238),
        City("Kielce", 50.8661, 20.6286),
        City("Kraków", 50.0647, 19.9450),
        City("Lublin", 51.2465, 22.5684),
        City("Łódź", 51.7592, 19.4560),
        City("Olsztyn", 53.7784, 20.4801),
        City("Opole", 50.6751, 17.9213),
        City("Poznań", 52.4064, 16.9252),
        City("Rzeszów", 50.0412, 21.9991),
        City("Szczecin", 53.4285, 14.5528),
        City("Toruń", 53.0138, 18.5984),
        City("Warszawa", 52.2297, 21.0122),
        City("Wrocław", 51.1079, 17.0385),
        City("Zielona Góra", 51.9356, 15.5062),
    )

    val DEFAULT: City = ALL.first { it.name == "Toruń" }

    fun byName(name: String): City? = ALL.firstOrNull { it.name == name }

    fun names(): List<String> = ALL.map { it.name }
}
