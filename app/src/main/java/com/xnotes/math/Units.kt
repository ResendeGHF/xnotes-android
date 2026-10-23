package com.xnotes.math

import kotlin.math.PI

/** A unit's relation to its category's base unit: base = value·factor + offset. */
data class UnitDef(
    val name: String,
    val symbol: String,
    val factor: Double,
    val offset: Double = 0.0,
) {
    fun toBase(value: Double): Double = value * factor + offset
    fun fromBase(base: Double): Double = (base - offset) / factor
}

data class UnitCategoryInfo(val name: String, val units: List<UnitDef>) {
    fun unit(symbol: String): UnitDef? = units.firstOrNull { it.symbol == symbol || it.name == symbol }
}

/**
 * An offline unit catalogue covering the SI base and derived units, the common imperial and US
 * customary units, astronomy, particle physics and information units. Each category converts
 * through one base unit, so adding a unit never needs a pairwise table.
 */
object Units {

    private fun u(name: String, symbol: String, factor: Double, offset: Double = 0.0) =
        UnitDef(name, symbol, factor, offset)

    val categories: List<UnitCategoryInfo> = listOf(
        UnitCategoryInfo(
            "Length",
            listOf(
                u("metre", "m", 1.0), u("kilometre", "km", 1000.0), u("centimetre", "cm", 0.01),
                u("millimetre", "mm", 0.001), u("micrometre", "µm", 1e-6), u("nanometre", "nm", 1e-9),
                u("picometre", "pm", 1e-12), u("femtometre", "fm", 1e-15), u("ångström", "Å", 1e-10),
                u("inch", "in", 0.0254), u("foot", "ft", 0.3048), u("yard", "yd", 0.9144),
                u("mile", "mi", 1609.344), u("nautical mile", "nmi", 1852.0),
                u("light-year", "ly", 9.4607304725808e15), u("astronomical unit", "au", 1.495978707e11),
                u("parsec", "pc", 3.085677581491367e16), u("light-second", "ls", 299792458.0),
                u("mil", "mil", 2.54e-5), u("furlong", "fur", 201.168), u("fathom", "ftm", 1.8288),
                u("rod", "rd", 5.0292), u("chain", "ch", 20.1168), u("point", "pt", 0.0254 / 72.0),
                u("pica", "pc_typ", 0.0254 / 6.0), u("bohr radius", "a₀", 5.29177210903e-11),
                u("planck length", "ℓP", 1.616255e-35), u("solar radius", "R☉", 6.957e8),
                u("earth radius", "R⊕", 6.371e6), u("jupiter radius", "R♃", 6.9911e7),
            ),
        ),
        UnitCategoryInfo(
            "Mass",
            listOf(
                u("kilogram", "kg", 1.0), u("gram", "g", 0.001), u("milligram", "mg", 1e-6),
                u("microgram", "µg", 1e-9), u("tonne", "t", 1000.0), u("pound", "lb", 0.45359237),
                u("ounce", "oz", 0.028349523125), u("stone", "st", 6.35029318),
                u("US ton", "ton", 907.18474), u("UK ton", "long ton", 1016.0469088),
                u("slug", "slug", 14.59390294), u("grain", "gr", 6.479891e-5),
                u("carat", "ct", 0.0002), u("atomic mass unit", "u", 1.66053906660e-27),
                u("electron mass", "mₑ", 9.1093837015e-31), u("proton mass", "mₚ", 1.67262192369e-27),
                u("neutron mass", "mₙ", 1.67492749804e-27), u("solar mass", "M☉", 1.98847e30),
                u("earth mass", "M⊕", 5.9722e24), u("jupiter mass", "M♃", 1.89813e27),
                u("eV/c²", "eV/c²", 1.7826619216279e-36), u("planck mass", "mP", 2.176434e-8),
            ),
        ),
        UnitCategoryInfo(
            "Time",
            listOf(
                u("second", "s", 1.0), u("millisecond", "ms", 1e-3), u("microsecond", "µs", 1e-6),
                u("nanosecond", "ns", 1e-9), u("picosecond", "ps", 1e-12), u("minute", "min", 60.0),
                u("hour", "h", 3600.0), u("day", "d", 86400.0), u("week", "wk", 604800.0),
                u("fortnight", "fortnight", 1209600.0), u("month (mean)", "mo", 2629746.0),
                u("year (Julian)", "yr", 31557600.0), u("sidereal day", "sidereal day", 86164.0905),
                u("sidereal year", "sidereal yr", 31558149.76), u("decade", "decade", 315576000.0),
                u("century", "century", 3155760000.0), u("millennium", "millennium", 3.15576e10),
                u("shake", "shake", 1e-8), u("planck time", "tP", 5.391247e-44),
            ),
        ),
        UnitCategoryInfo(
            "Temperature",
            listOf(
                u("kelvin", "K", 1.0), u("degree Celsius", "°C", 1.0, 273.15),
                u("degree Fahrenheit", "°F", 5.0 / 9.0, 255.3722222222222),
                u("degree Rankine", "°R", 5.0 / 9.0), u("electronvolt", "eV", 1.602176634e-19 / 1.380649e-23),
            ),
        ),
        UnitCategoryInfo(
            "Electric current",
            listOf(
                u("ampere", "A", 1.0), u("milliampere", "mA", 1e-3), u("microampere", "µA", 1e-6),
                u("nanoampere", "nA", 1e-9), u("kiloampere", "kA", 1e3), u("abampere", "abA", 10.0),
                u("statampere", "statA", 3.335641e-10),
            ),
        ),
        UnitCategoryInfo(
            "Amount of substance",
            listOf(
                u("mole", "mol", 1.0), u("millimole", "mmol", 1e-3), u("micromole", "µmol", 1e-6),
                u("kilomole", "kmol", 1e3),
            ),
        ),
        UnitCategoryInfo(
            "Luminous intensity",
            listOf(
                u("candela", "cd", 1.0), u("millicandela", "mcd", 1e-3),
                u("kilocandela", "kcd", 1e3), u("candlepower", "cp", 1.0),
            ),
        ),
        UnitCategoryInfo(
            "Angle",
            listOf(
                u("radian", "rad", 1.0), u("degree", "°", PI / 180.0), u("gradian", "grad", PI / 200.0),
                u("turn", "turn", 2 * PI), u("arcminute", "′", PI / 10800.0),
                u("arcsecond", "″", PI / 648000.0), u("milliradian", "mrad", 1e-3),
                u("microradian", "µrad", 1e-6),
            ),
        ),
        UnitCategoryInfo(
            "Area",
            listOf(
                u("square metre", "m²", 1.0), u("square kilometre", "km²", 1e6),
                u("square centimetre", "cm²", 1e-4), u("square millimetre", "mm²", 1e-6),
                u("hectare", "ha", 1e4), u("are", "a", 100.0), u("acre", "acre", 4046.8564224),
                u("square foot", "ft²", 0.09290304), u("square inch", "in²", 0.00064516),
                u("square yard", "yd²", 0.83612736), u("square mile", "mi²", 2589988.110336),
                u("barn", "b", 1e-28), u("square astronomical unit", "au²", 2.237952e22),
            ),
        ),
        UnitCategoryInfo(
            "Volume",
            listOf(
                u("cubic metre", "m³", 1.0), u("litre", "L", 0.001), u("millilitre", "mL", 1e-6),
                u("cubic centimetre", "cm³", 1e-6), u("cubic millimetre", "mm³", 1e-9),
                u("cubic foot", "ft³", 0.028316846592), u("cubic inch", "in³", 1.6387064e-5),
                u("cubic yard", "yd³", 0.764554857984), u("US gallon", "gal", 0.003785411784),
                u("US quart", "qt", 0.000946352946), u("US pint", "pt", 0.000473176473),
                u("US cup", "cup", 0.0002365882365), u("US fluid ounce", "fl oz", 2.95735295625e-5),
                u("US tablespoon", "tbsp", 1.47867647813e-5), u("US teaspoon", "tsp", 4.92892159375e-6),
                u("UK gallon", "UK gal", 0.00454609), u("UK pint", "UK pt", 0.00056826125),
                u("barrel (oil)", "bbl", 0.158987294928), u("bushel (US)", "bu", 0.03523907016688),
            ),
        ),
        UnitCategoryInfo(
            "Speed",
            listOf(
                u("metre/second", "m/s", 1.0), u("kilometre/hour", "km/h", 1.0 / 3.6),
                u("mile/hour", "mph", 0.44704), u("knot", "kn", 0.514444444444),
                u("foot/second", "ft/s", 0.3048), u("mach (sea level)", "Ma", 340.29),
                u("speed of light", "c", 299792458.0),
            ),
        ),
        UnitCategoryInfo(
            "Acceleration",
            listOf(
                u("metre/second²", "m/s²", 1.0), u("galileo", "Gal", 0.01),
                u("foot/second²", "ft/s²", 0.3048), u("standard gravity", "g₀", 9.80665),
            ),
        ),
        UnitCategoryInfo(
            "Force",
            listOf(
                u("newton", "N", 1.0), u("kilonewton", "kN", 1e3), u("dyne", "dyn", 1e-5),
                u("pound-force", "lbf", 4.4482216152605), u("kilogram-force", "kgf", 9.80665),
                u("poundal", "pdl", 0.138254954376),
            ),
        ),
        UnitCategoryInfo(
            "Energy",
            listOf(
                u("joule", "J", 1.0), u("kilojoule", "kJ", 1e3), u("megajoule", "MJ", 1e6),
                u("calorie", "cal", 4.184), u("kilocalorie", "kcal", 4184.0),
                u("watt-hour", "Wh", 3600.0), u("kilowatt-hour", "kWh", 3.6e6),
                u("electronvolt", "eV", 1.602176634e-19), u("kiloelectronvolt", "keV", 1.602176634e-16),
                u("megaelectronvolt", "MeV", 1.602176634e-13), u("gigaelectronvolt", "GeV", 1.602176634e-10),
                u("teraelectronvolt", "TeV", 1.602176634e-7), u("erg", "erg", 1e-7),
                u("British thermal unit", "BTU", 1055.05585262), u("therm (US)", "therm", 1.05505585262e8),
                u("foot-pound", "ft·lbf", 1.3558179483314), u("ton of TNT", "tTNT", 4.184e9),
                u("hartree", "Eh", 4.3597447222071e-18), u("rydberg", "Ry", 2.1798723611035e-18),
            ),
        ),
        UnitCategoryInfo(
            "Power",
            listOf(
                u("watt", "W", 1.0), u("kilowatt", "kW", 1e3), u("megawatt", "MW", 1e6),
                u("gigawatt", "GW", 1e9), u("horsepower (mechanical)", "hp", 745.6998715823),
                u("horsepower (metric)", "PS", 735.49875), u("BTU/hour", "BTU/h", 0.29307107017),
                u("calorie/second", "cal/s", 4.184),
            ),
        ),
        UnitCategoryInfo(
            "Pressure",
            listOf(
                u("pascal", "Pa", 1.0), u("kilopascal", "kPa", 1e3), u("megapascal", "MPa", 1e6),
                u("gigapascal", "GPa", 1e9), u("bar", "bar", 1e5), u("millibar", "mbar", 100.0),
                u("atmosphere", "atm", 101325.0), u("torr", "Torr", 101325.0 / 760.0),
                u("millimetre of mercury", "mmHg", 133.322387415), u("inch of mercury", "inHg", 3386.389),
                u("pound/square inch", "psi", 6894.757293168), u("dyne/centimetre²", "dyn/cm²", 0.1),
            ),
        ),
        UnitCategoryInfo(
            "Frequency",
            listOf(
                u("hertz", "Hz", 1.0), u("kilohertz", "kHz", 1e3), u("megahertz", "MHz", 1e6),
                u("gigahertz", "GHz", 1e9), u("terahertz", "THz", 1e12),
                u("revolutions/minute", "rpm", 1.0 / 60.0), u("radian/second", "rad/s", 1.0 / (2 * PI)),
            ),
        ),
        UnitCategoryInfo(
            "Charge",
            listOf(
                u("coulomb", "C", 1.0), u("millicoulomb", "mC", 1e-3), u("microcoulomb", "µC", 1e-6),
                u("nanocoulomb", "nC", 1e-9), u("picocoulomb", "pC", 1e-12),
                u("ampere-hour", "Ah", 3600.0), u("milliampere-hour", "mAh", 3.6),
                u("elementary charge", "e", 1.602176634e-19), u("statcoulomb", "statC", 3.335641e-10),
            ),
        ),
        UnitCategoryInfo(
            "Voltage",
            listOf(
                u("volt", "V", 1.0), u("millivolt", "mV", 1e-3), u("microvolt", "µV", 1e-6),
                u("kilovolt", "kV", 1e3), u("megavolt", "MV", 1e6), u("abvolt", "abV", 1e-8),
            ),
        ),
        UnitCategoryInfo(
            "Capacitance",
            listOf(
                u("farad", "F", 1.0), u("millifarad", "mF", 1e-3), u("microfarad", "µF", 1e-6),
                u("nanofarad", "nF", 1e-9), u("picofarad", "pF", 1e-12), u("statfarad", "statF", 1.112650e-12),
            ),
        ),
        UnitCategoryInfo(
            "Resistance",
            listOf(
                u("ohm", "Ω", 1.0), u("milliohm", "mΩ", 1e-3), u("kiloohm", "kΩ", 1e3),
                u("megaohm", "MΩ", 1e6), u("gigaohm", "GΩ", 1e9),
            ),
        ),
        UnitCategoryInfo(
            "Conductance",
            listOf(u("siemens", "S", 1.0), u("millisiemens", "mS", 1e-3), u("microsiemens", "µS", 1e-6)),
        ),
        UnitCategoryInfo(
            "Magnetic flux",
            listOf(u("weber", "Wb", 1.0), u("maxwell", "Mx", 1e-8), u("tesla square metre", "T·m²", 1.0)),
        ),
        UnitCategoryInfo(
            "Magnetic flux density",
            listOf(
                u("tesla", "T", 1.0), u("millitesla", "mT", 1e-3), u("microtesla", "µT", 1e-6),
                u("gauss", "G", 1e-4), u("kilogauss", "kG", 0.1),
            ),
        ),
        UnitCategoryInfo(
            "Inductance",
            listOf(
                u("henry", "H", 1.0), u("millihenry", "mH", 1e-3), u("microhenry", "µH", 1e-6),
                u("nanohenry", "nH", 1e-9), u("abhenry", "abH", 1e-9),
            ),
        ),
        UnitCategoryInfo(
            "Radioactivity",
            listOf(
                u("becquerel", "Bq", 1.0), u("kilobecquerel", "kBq", 1e3), u("megabecquerel", "MBq", 1e6),
                u("gigabecquerel", "GBq", 1e9), u("curie", "Ci", 3.7e10),
                u("millicurie", "mCi", 3.7e7), u("microcurie", "µCi", 3.7e4),
                u("rutherford", "Rd", 1e6),
            ),
        ),
        UnitCategoryInfo(
            "Absorbed dose",
            listOf(
                u("gray", "Gy", 1.0), u("milligray", "mGy", 1e-3), u("rad", "rad_dose", 0.01),
            ),
        ),
        UnitCategoryInfo(
            "Equivalent dose",
            listOf(
                u("sievert", "Sv", 1.0), u("millisievert", "mSv", 1e-3), u("microsievert", "µSv", 1e-6),
                u("rem", "rem", 0.01), u("millirem", "mrem", 1e-5),
            ),
        ),
        UnitCategoryInfo(
            "Catalytic activity",
            listOf(u("katal", "kat", 1.0), u("enzyme unit", "U", 1.6666667e-8)),
        ),
        UnitCategoryInfo(
            "Data",
            listOf(
                u("bit", "bit", 1.0), u("byte", "B", 8.0), u("kilobyte (SI)", "kB", 8000.0),
                u("megabyte (SI)", "MB", 8e6), u("gigabyte (SI)", "GB", 8e9),
                u("terabyte (SI)", "TB", 8e12), u("petabyte (SI)", "PB", 8e15),
                u("kibibyte", "KiB", 8192.0), u("mebibyte", "MiB", 8388608.0),
                u("gibibyte", "GiB", 8589934592.0), u("tebibyte", "TiB", 8.796093022208e12),
                u("pebibyte", "PiB", 9.007199254741e15), u("nibble", "nibble", 4.0),
            ),
        ),
        UnitCategoryInfo(
            "Density",
            listOf(
                u("kilogram/metre³", "kg/m³", 1.0), u("gram/centimetre³", "g/cm³", 1000.0),
                u("gram/millilitre", "g/mL", 1000.0), u("pound/foot³", "lb/ft³", 16.01846337396),
                u("pound/inch³", "lb/in³", 27679.9047102),
            ),
        ),
        UnitCategoryInfo(
            "Dynamic viscosity",
            listOf(
                u("pascal-second", "Pa·s", 1.0), u("poise", "P", 0.1), u("centipoise", "cP", 0.001),
            ),
        ),
        UnitCategoryInfo(
            "Kinematic viscosity",
            listOf(
                u("square metre/second", "m²/s", 1.0), u("stokes", "St", 1e-4), u("centistokes", "cSt", 1e-6),
            ),
        ),
        UnitCategoryInfo(
            "Wavenumber",
            listOf(
                u("reciprocal metre", "m⁻¹", 1.0), u("reciprocal centimetre", "cm⁻¹", 100.0),
            ),
        ),
    )

    fun category(name: String): UnitCategoryInfo? = categories.firstOrNull { it.name == name }

    fun convert(value: Double, from: UnitDef, to: UnitDef): Double = to.fromBase(from.toBase(value))

    fun convert(value: Double, category: String, from: String, to: String): Double? {
        val c = category(category) ?: return null
        val a = c.unit(from) ?: return null
        val b = c.unit(to) ?: return null
        return convert(value, a, b)
    }

    /** A compact table of all same-category equivalents for one value. */
    fun equivalents(value: Double, category: String, from: String): List<Pair<UnitDef, Double>> {
        val c = category(category) ?: return emptyList()
        val a = c.unit(from) ?: return emptyList()
        return c.units.map { it to convert(value, a, it) }
    }
}
