package dotty

/**
 *  @author Nicolas Stucki
 */
package object meta {

  def quote[T](code: T): Expr[T] =
    throw new Error("Quoted expression was not pickled")

  def spliceHole[T](id: Int): T =
    throw new Exception(s"Splice hole $id was not filled with expression")

}
