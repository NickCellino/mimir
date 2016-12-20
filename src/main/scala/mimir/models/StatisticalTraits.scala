package mimir.models

import mimir.algebra._

trait FiniteDiscreteDomain 
{
  def getDomain(idx:Int, args:List[PrimitiveValue]): Seq[(PrimitiveValue,Double)]
}
