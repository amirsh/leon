/* Copyright 2009-2014 EPFL, Lausanne */

package leon
package utils

trait Interruptible {
  def interrupt(): Unit
  def recoverInterrupt(): Unit
}
