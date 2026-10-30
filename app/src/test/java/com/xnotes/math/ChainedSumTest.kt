package com.xnotes.math
import org.junit.Test
import org.junit.Assert.*
class ChainedSumTest {
    private fun eval(t: String, vars: Map<String,Double> = emptyMap()): Double =
        Evaluator.evaluate(ExpressionParser.parse(t), vars).re
    @Test fun chainedSingle() {
        assertEquals(55.0, eval("sum(n,1,10)(n)"), 1e-9)
        assertEquals(55.0, eval("sum(n,1,10,n)"), 1e-9)
    }
    @Test fun chainedDouble() {
        // sum_n=0..2 sum_m=0..2 (n+m) = ?
        // n=0: 0+1+2=3; n=1:1+2+3=6; n=2:2+3+4=9 => 18
        assertEquals(18.0, eval("sum(n,0,2)(m,0,2)(n+m)"), 1e-9)
    }
    @Test fun prodChained() {
        assertEquals(24.0, eval("prod(n,1,4)(n)"), 1e-9)
    }
    @Test fun expSeries() {
        // sum(n,0,10)(x^n/n!) at x=1 ~= e
        val v = eval("sum(n,0,10)(x^n/n!)", mapOf("x" to 1.0))
        assertEquals(2.7182818011463845, v, 1e-9)
    }
    @Test fun mixedNested() {
        // sum_n=1..2 prod_m=1..2 (n*m) = sum_n (n*1 * n*2)= sum_n 2n^2 =2+8=10
        assertEquals(10.0, eval("sum(n,1,2)(prod(m,1,2)(n*m))"), 1e-9)
    }
}
