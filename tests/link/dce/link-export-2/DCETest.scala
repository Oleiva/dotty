
object DCETest {
  @scala.annotation.internal.DoNotDCE def dceTest: Unit = {
    System.out.println("dceTest")
    Test.shouldDCE(Foo.bar())
    Foo.foo()
  }
}

object Foo {
  @scala.export def foo(): Unit = System.out.println(42)
  def bar(): Unit = System.out.println(43)
}
