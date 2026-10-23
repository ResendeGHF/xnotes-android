package com.xnotes.math

/** One entry in the in-app function reference. */
data class MathFunctionInfo(
    val name: String,
    val aliases: List<String>,
    val category: String,
    val syntax: String,
    val description: String,
)

data class MathConstantInfo(
    val name: String,
    val value: String,
    val description: String,
)

/**
 * The searchable reference behind the Functions tab, and the source of the sets the parser uses to
 * recognise a function written without brackets. A function may have several spellings; the first
 * name is the canonical one shown in the reference.
 */
object MathCatalog {

    private fun f(
        name: String,
        category: String,
        syntax: String,
        description: String,
        vararg aliases: String,
    ) = MathFunctionInfo(name, aliases.toList(), category, syntax, description)

    val functions: List<MathFunctionInfo> = listOf(
        // Arithmetic and elementary functions
        f("abs", "Elementary", "abs(x)", "Absolute value; also the complex modulus.", "absval"),
        f("sign", "Elementary", "sign(x)", "Sign: −1, 0 or 1.", "sgn"),
        f("floor", "Elementary", "floor(x)", "Greatest integer not above x."),
        f("ceil", "Elementary", "ceil(x)", "Least integer not below x.", "ceiling"),
        f("round", "Elementary", "round(x)", "Nearest integer, halves away from zero."),
        f("trunc", "Elementary", "trunc(x)", "Integer part, towards zero."),
        f("frac", "Elementary", "frac(x)", "Fractional part, x − floor(x)."),
        f("min", "Elementary", "min(a, b, …)", "Smallest argument."),
        f("max", "Elementary", "max(a, b, …)", "Largest argument."),
        f("mod", "Elementary", "mod(a, b)", "Remainder of a divided by b.", "rem"),
        f("hypot", "Elementary", "hypot(x, y)", "Euclidean length √(x²+y²)."),
        f("clamp", "Elementary", "clamp(x, lo, hi)", "Restrict x to the interval [lo, hi]."),
        f("sqrt", "Elementary", "sqrt(x)", "Square root; complex for negative real arguments."),
        f("cbrt", "Elementary", "cbrt(x)", "Real cube root."),
        f("root", "Elementary", "root(x, n)", "The n-th root; complex when needed."),
        f("exp", "Elementary", "exp(x)", "Exponential eˣ."),
        f("ln", "Elementary", "ln(x)", "Natural logarithm; complex for negative real arguments."),
        f("log", "Elementary", "log(x) or log(x, b)", "Base-10 logarithm, or logarithm to base b.", "log10"),
        f("log2", "Elementary", "log2(x)", "Base-2 logarithm."),
        f("logn", "Elementary", "logn(x, b)", "Logarithm to base b.", "logb"),
        f("sinc", "Elementary", "sinc(x)", "sin(x)/x, with sinc(0)=1."),

        // Trigonometric and hyperbolic functions
        f("sin", "Trigonometry", "sin(x)", "Sine. Angle mode applies to the argument."),
        f("cos", "Trigonometry", "cos(x)", "Cosine."),
        f("tan", "Trigonometry", "tan(x)", "Tangent."),
        f("cot", "Trigonometry", "cot(x)", "Cotangent."),
        f("sec", "Trigonometry", "sec(x)", "Secant."),
        f("csc", "Trigonometry", "csc(x)", "Cosecant."),
        f("asin", "Trigonometry", "asin(x)", "Inverse sine; complex outside [−1,1].", "arcsin"),
        f("acos", "Trigonometry", "acos(x)", "Inverse cosine; complex outside [−1,1].", "arccos"),
        f("atan", "Trigonometry", "atan(x)", "Inverse tangent.", "arctan"),
        f("atan2", "Trigonometry", "atan2(y, x)", "Four-quadrant inverse tangent.", "arctan2"),
        f("acot", "Trigonometry", "acot(x)", "Inverse cotangent.", "arccot"),
        f("asec", "Trigonometry", "asec(x)", "Inverse secant."),
        f("acsc", "Trigonometry", "acsc(x)", "Inverse cosecant."),
        f("sinh", "Trigonometry", "sinh(x)", "Hyperbolic sine."),
        f("cosh", "Trigonometry", "cosh(x)", "Hyperbolic cosine."),
        f("tanh", "Trigonometry", "tanh(x)", "Hyperbolic tangent."),
        f("coth", "Trigonometry", "coth(x)", "Hyperbolic cotangent."),
        f("sech", "Trigonometry", "sech(x)", "Hyperbolic secant."),
        f("csch", "Trigonometry", "csch(x)", "Hyperbolic cosecant."),
        f("asinh", "Trigonometry", "asinh(x)", "Inverse hyperbolic sine.", "arcsinh"),
        f("acosh", "Trigonometry", "acosh(x)", "Inverse hyperbolic cosine.", "arccosh"),
        f("atanh", "Trigonometry", "atanh(x)", "Inverse hyperbolic tangent.", "arctanh"),

        // Special functions
        f("gamma", "Special functions", "gamma(z)", "Euler gamma function Γ(z), analytic in the complex plane.", "Γ"),
        f("lgamma", "Special functions", "lgamma(z)", "Logarithm of |Γ(z)|.", "lngamma"),
        f("digamma", "Special functions", "digamma(z)", "Logarithmic derivative ψ(z)=Γ′(z)/Γ(z)."),
        f("polygamma", "Special functions", "polygamma(n, z)", "The n-th polygamma function ψ⁽ⁿ⁾(z)."),
        f("beta", "Special functions", "beta(a, b)", "Euler beta function B(a,b)."),
        f("lnbeta", "Special functions", "lnbeta(a, b)", "Logarithm of the beta function."),
        f("factorial", "Special functions", "factorial(n)", "Factorial n! = Γ(n+1)."),
        f("doublefactorial", "Special functions", "doublefactorial(n)", "Double factorial n!!."),
        f("binomial", "Special functions", "binomial(n, k)", "Binomial coefficient C(n,k).", "choose", "ncr"),
        f("rising", "Special functions", "rising(x, n)", "Rising factorial x(x+1)…(x+n−1).", "pochhammer"),
        f("falling", "Special functions", "falling(x, n)", "Falling factorial x(x−1)…(x−n+1)."),
        f("harmonic", "Special functions", "harmonic(n)", "Harmonic number Hₙ (or H(x) via ψ)."),
        f("erf", "Special functions", "erf(x)", "Error function."),
        f("erfc", "Special functions", "erfc(x)", "Complementary error function 1−erf(x)."),
        f("erfi", "Special functions", "erfi(x)", "Imaginary error function."),
        f("erfinv", "Special functions", "erfinv(x)", "Inverse error function, |x|<1."),
        f("dawson", "Special functions", "dawson(x)", "Dawson's integral F(x)."),
        f("fresnelS", "Special functions", "fresnelS(x)", "Fresnel sine integral S(x)."),
        f("fresnelC", "Special functions", "fresnelC(x)", "Fresnel cosine integral C(x)."),
        f("zeta", "Special functions", "zeta(s)", "Riemann zeta function ζ(s)."),
        f("hurwitzzeta", "Special functions", "hurwitzzeta(s, a)", "Hurwitz zeta ζ(s,a).", "zetah"),
        f("eta", "Special functions", "eta(s)", "Dirichlet eta function η(s)."),
        f("polylog", "Special functions", "polylog(s, x)", "Polylogarithm Liₛ(x)."),
        f("li", "Special functions", "li(x)", "Logarithmic integral li(x)."),
        f("ei", "Special functions", "ei(x)", "Exponential integral Ei(x)."),
        f("e1", "Special functions", "e1(x)", "Exponential integral E₁(x)."),
        f("si", "Special functions", "si(x)", "Sine integral Si(x)."),
        f("ci", "Special functions", "ci(x)", "Cosine integral Ci(x)."),
        f("shi", "Special functions", "shi(x)", "Hyperbolic sine integral Shi(x)."),
        f("chi", "Special functions", "chi(x)", "Hyperbolic cosine integral Chi(x)."),
        f("lambertw", "Special functions", "lambertw(x[, branch])", "Lambert W function; branch 0 or −1."),
        f("agm", "Special functions", "agm(a, b)", "Arithmetic–geometric mean."),
        f("bernoulli", "Special functions", "bernoulli(n)", "Bernoulli number Bₙ."),
        f("incompletegamma", "Special functions", "incompletegamma(a, x)", "Lower incomplete gamma γ(a,x).", "gammainc"),
        f("incompletebeta", "Special functions", "incompletebeta(a, b, x)", "Regularized incomplete beta Iₓ(a,b).", "betainc"),
        f("hypergeom", "Special functions", "hypergeom(a, b, c, z)", "Gauss hypergeometric ₂F₁(a,b;c;z).", "2f1"),

        // Bessel and friends
        f("besselj", "Bessel & Airy", "besselj(n, x)", "Bessel function of the first kind Jₙ(x).", "jn"),
        f("bessely", "Bessel & Airy", "bessely(n, x)", "Bessel function of the second kind Yₙ(x).", "yn"),
        f("besseli", "Bessel & Airy", "besseli(n, x)", "Modified Bessel function Iₙ(x).", "in"),
        f("besselk", "Bessel & Airy", "besselk(n, x)", "Modified Bessel function Kₙ(x).", "kn"),
        f("airy", "Bessel & Airy", "airy(x)", "Airy function Ai(x).", "airyai"),
        f("airybi", "Bessel & Airy", "airybi(x)", "Airy function Bi(x)."),

        // Elliptic and orthogonal polynomials
        f("ellipticK", "Elliptic & orthogonal", "ellipticK(m)", "Complete elliptic integral K(m), parameter m."),
        f("ellipticE", "Elliptic & orthogonal", "ellipticE(m)", "Complete elliptic integral E(m), parameter m."),
        f("ellipticPi", "Elliptic & orthogonal", "ellipticPi(n, m)", "Complete elliptic integral Π(n,m)."),
        f("legendreP", "Elliptic & orthogonal", "legendreP(n, x)", "Legendre polynomial Pₙ(x)."),
        f("legendreQ", "Elliptic & orthogonal", "legendreQ(n, x)", "Legendre function Qₙ(x)."),
        f("hermite", "Elliptic & orthogonal", "hermite(n, x)", "Physicists' Hermite polynomial Hₙ(x)."),
        f("laguerre", "Elliptic & orthogonal", "laguerre(n, x)", "Laguerre polynomial Lₙ(x)."),
        f("chebyshevT", "Elliptic & orthogonal", "chebyshevT(n, x)", "Chebyshev polynomial Tₙ(x)."),
        f("chebyshevU", "Elliptic & orthogonal", "chebyshevU(n, x)", "Chebyshev polynomial Uₙ(x)."),

        // Number theory and combinatorics
        f("gcd", "Number theory", "gcd(a, b)", "Greatest common divisor."),
        f("lcm", "Number theory", "lcm(a, b)", "Least common multiple."),
        f("isprime", "Number theory", "isprime(n)", "1 if n is prime, else 0."),
        f("prime", "Number theory", "prime(n)", "The n-th prime, with prime(1)=2."),
        f("totient", "Number theory", "totient(n)", "Euler totient φ(n).", "phi_n"),
        f("mobius", "Number theory", "mobius(n)", "Möbius function μ(n)."),
        f("sigma", "Number theory", "sigma(n[, k])", "Sum of the k-th powers of divisors."),
        f("fib", "Number theory", "fib(n)", "Fibonacci number Fₙ.", "fibonacci"),
        f("partitions", "Number theory", "partitions(n)", "Number of integer partitions p(n)."),

        // Waves and shapes
        f("heaviside", "Waves & shapes", "heaviside(x)", "Heaviside step: 0 below 0, 1 at and above 0.", "step"),
        f("square", "Waves & shapes", "square(x)", "Square wave with period 2π: sign(sin x)."),
        f("triangle", "Waves & shapes", "triangle(x)", "Triangle wave with period 2π."),
        f("sawtooth", "Waves & shapes", "sawtooth(x)", "Sawtooth wave with period 2π."),
        f("gauss", "Waves & shapes", "gauss(x, μ, σ)", "Gaussian bump exp(−((x−μ)/σ)²/2).", "gaussian"),
        f("lorentzian", "Waves & shapes", "lorentzian(x, x₀, γ)", "Lorentzian 1/(1+((x−x₀)/γ)²)."),
        f("bump", "Waves & shapes", "bump(x, a, b)", "Smooth bump on [a,b]."),
    )

    val constants: List<MathConstantInfo> = listOf(
        MathConstantInfo("pi", "3.141592653589793", "Ratio of a circle's circumference to its diameter."),
        MathConstantInfo("tau", "6.283185307179586", "2π."),
        MathConstantInfo("e", "2.718281828459045", "Euler's number, base of the natural logarithm."),
        MathConstantInfo("i", "i", "Imaginary unit, i²=−1; also written j."),
        MathConstantInfo("phi", "1.618033988749895", "Golden ratio (1+√5)/2."),
        MathConstantInfo("gamma_e", "0.5772156649015329", "Euler–Mascheroni constant γ."),
        MathConstantInfo("inf", "∞", "Positive infinity."),
        MathConstantInfo("nan", "NaN", "Not a number."),
        MathConstantInfo("c", "299792458", "Speed of light in vacuum, m/s."),
        MathConstantInfo("hbar", "1.054571817e−34", "Reduced Planck constant, J·s."),
        MathConstantInfo("h", "6.62607015e−34", "Planck constant, J·s."),
        MathConstantInfo("G", "6.67430e−11", "Newtonian gravitational constant, m³·kg⁻¹·s⁻²."),
        MathConstantInfo("kB", "1.380649e−23", "Boltzmann constant, J/K."),
        MathConstantInfo("Na", "6.02214076e23", "Avogadro constant, mol⁻¹."),
        MathConstantInfo("R", "8.314462618", "Molar gas constant, J·mol⁻¹·K⁻¹."),
        MathConstantInfo("qe", "1.602176634e−19", "Elementary charge, C."),
        MathConstantInfo("me", "9.1093837015e−31", "Electron mass, kg."),
        MathConstantInfo("mp", "1.67262192369e−27", "Proton mass, kg."),
        MathConstantInfo("mn", "1.67492749804e−27", "Neutron mass, kg."),
        MathConstantInfo("mu0", "1.25663706212e−6", "Vacuum permeability, H/m."),
        MathConstantInfo("eps0", "8.8541878128e−12", "Vacuum permittivity, F/m."),
        MathConstantInfo("alpha_fs", "0.0072973525693", "Fine-structure constant α."),
        MathConstantInfo("bohr", "5.29177210903e−11", "Bohr radius, m."),
        MathConstantInfo("Ry", "13.605693122994", "Rydberg energy, eV."),
        MathConstantInfo("eV", "1.602176634e−19", "Electron volt in joules."),
        MathConstantInfo("sigma_sb", "5.670374419e−8", "Stefan–Boltzmann constant, W·m⁻²·K⁻⁴."),
    )

    val categories: List<String> = functions.map { it.category }.distinct()

    val unaryFunctionNames: Set<String> = functions
        .filter { it.syntax.contains("(x)") || it.syntax.contains("(z)") || it.syntax.contains("(n)") }
        .flatMap { listOf(it.name.lowercase()) + it.aliases.map(String::lowercase) }
        .toSet()

    val constantNames: Set<String> = constants.map { it.name.lowercase() }.toSet() +
        setOf("π", "∞", "oo", "infinity", "j", "euler", "goldenratio", "c0", "kb", "na", "n_a")

    /** All spellings the evaluator resolves to a callable function. */
    val functionAliases: Map<String, MathFunctionInfo> = buildMap {
        for (f in functions) {
            put(f.name.lowercase(), f)
            for (a in f.aliases) put(a.lowercase(), f)
        }
    }
}
