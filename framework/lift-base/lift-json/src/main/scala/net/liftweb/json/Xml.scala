/*
 * Copyright 2009-2010 WorldWide Conferencing, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.liftweb {
package json {

/** Functions to convert between JSON and XML.
 */
object Xml {
  import JsonAST._
  import scala.xml._

  /** Convert given XML to JSON.
   * <p>
   * Following rules are used in conversion.
   * <ul>
   *   <li>XML leaf element is converted to JSON string</li>
   *   <li>XML parent element is converted to JSON object and its children to JSON fields</li>
   *   <li>XML elements with same name at same level are converted to JSON array</li>
   *   <li>XML attributes are converted to JSON fields</li>
   * </ul>
   * <p>
   * Example:<pre>
   * scala> val xml =
   *     &lt;users&gt;
   *       &lt;user&gt;
   *         &lt;id&gt;1&lt;/id&gt;
   *         &lt;name&gt;Harry&lt;/name&gt;
   *       &lt;/user&gt;
   *       &lt;user&gt;
   *         &lt;id&gt;2&lt;/id&gt;
   *         &lt;name&gt;David&lt;/name&gt;
   *       &lt;/user&gt;
   *     &lt;/users&gt;   
   *
   * scala> val json = toJson(xml)
   * scala> pretty(render(json))
   * 
   * {
   *   "users":{
   *     "user":[{
   *       "id":"1",
   *       "name":"Harry"
   *     },{
   *       "id":"2",
   *       "name":"David"
   *     }]
   *   }
   * }
   * </pre>
   *
   * Now, the above example has two problems. First, the id is converted to String while
   * we might want it as an Int. This is easy to fix by mapping JString(s) to JInt(s.toInt).
   * The second problem is more subtle. The conversion function decides to use JSON array
   * because there's more than one user-element in XML. Therefore a structurally equivalent
   * XML document which happens to have just one user-element will generate a JSON document
   * without JSON array. This is rarely a desired outcome. These both problems can be fixed
   * by following map function.
   * <pre>
   * json map {
   *   case JField("id", JString(s)) => JField("id", JInt(s.toInt))
   *   case JField("user", x: JObject) => JField("user", JArray(x :: Nil))
   *   case x => x 
   * }
   * </pre>
   */
  def toJson(xml: NodeSeq): JValue = {
    def empty_?(node: Node) = node.child.isEmpty

    /* Checks if given node is leaf element. For instance these are considered leafs:
     * <foo>bar</foo>, <foo>{ doSomething() }</foo>, etc.
     */
    def leaf_?(node: Node) = {
      def descendant(n: Node): List[Node] = n match {
        case g: Group => g.nodes.toList.flatMap(x => x :: descendant(x))
        case _ => n.child.toList.flatMap { x => x :: descendant(x) }
      }

      !descendant(node).find(_.isInstanceOf[Elem]).isDefined
    }

    def array_?(nodeNames: Seq[String]) = nodeNames.size != 1 && nodeNames.toList.removeDuplicates.size == 1
    def directChildren(n: Node): NodeSeq = n.child.filter(c => c.isInstanceOf[Elem])
    def nameOf(n: Node) = (if (n.prefix ne null) n.prefix + ":" else "") + n.label
    def buildAttrs(n: Node) = n.attributes.map((a: MetaData) => (a.key, XValue(a.value.text))).toList

    sealed trait XElem
    case class XValue(value: String) extends XElem
    case class XLeaf(value: (String, XElem), attrs: List[(String, XValue)]) extends XElem
    case class XNode(fields: List[(String, XElem)]) extends XElem
    case class XArray(elems: List[XElem]) extends XElem

    def toJValue(x: XElem): JValue = x match {
      case XValue(s) => JString(s)
      case XLeaf((name, value), attrs) => attrs match {
        case Nil => toJValue(value)
        case xs => JObject(JField(name, toJValue(value)) :: mkFields(xs))
      }
      case XNode(xs) => JObject(mkFields(xs))
      case XArray(elems) => JArray(elems.map(toJValue))
    }

    def mkFields(xs: List[(String, XElem)]) = 
      xs.flatMap { case (name, value) => (value, toJValue(value)) match {
        // This special case is needed to flatten nested objects which resulted from
        // XML attributes. Flattening keeps transformation more predicatable.  
        // <a><foo id="1">x</foo></a> -> {"a":{"foo":{"foo":"x","id":"1"}}} vs
        // <a><foo id="1">x</foo></a> -> {"a":{"foo":"x","id":"1"}}
        case (XLeaf(v, x :: xs), o: JObject) => o.obj
        case (_, json) => JField(name, json) :: Nil }}

    def buildNodes(xml: NodeSeq): List[XElem] = xml match {
      case n: Node =>
        if (empty_?(n)) XLeaf((nameOf(n), XValue("")), buildAttrs(n)) :: Nil
        else if (leaf_?(n)) XLeaf((nameOf(n), XValue(n.text)), buildAttrs(n)) :: Nil
        else {
          val children = directChildren(n)
          XNode(buildAttrs(n) ::: children.map(nameOf).toList.zip(buildNodes(children))) :: Nil
        }
      case nodes: NodeSeq => 
        val allLabels = nodes.map(_.label)
        if (array_?(allLabels)) {
          val arr = XArray(nodes.toList.flatMap { n => 
            if (leaf_?(n) && n.attributes.length == 0) XValue(n.text) :: Nil
            else buildNodes(n)
          })
          XLeaf((allLabels(0), arr), Nil) :: Nil
        } else nodes.toList.flatMap(buildNodes)
    }
    
    buildNodes(xml) match {
      case List(x @ XLeaf(_, _ :: _)) => toJValue(x)
      case List(x) => JObject(JField(nameOf(xml.first), toJValue(x)) :: Nil)
      case x => JArray(x.map(toJValue))
    }
  }

  /** Convert given JSON to XML.
   * <p>
   * Following rules are used in conversion.
   * <ul>
   *   <li>JSON primitives are converted to XML leaf elements</li>
   *   <li>JSON objects are converted to XML elements</li>
   *   <li>JSON arrays are recursively converted to XML elements</li>
   * </ul>
   * <p>
   * Use <code>map</code> function to preprocess JSON before conversion to adjust
   * the end result. For instance a common conversion is to encode arrays as comma
   * separated Strings since XML does not have array type.
   * <p><pre>
   * toXml(json map {
   *   case JField("nums",JArray(ns)) => JField("nums",JString(ns.map(_.values).mkString(",")))
   *   case x => x
   * })
   * </pre>
   */
  def toXml(json: JValue): NodeSeq = {
    def toXml(name: String, json: JValue): NodeSeq = json match {
      case JObject(fields) => new XmlNode(name, fields flatMap { f => toXml(f.name, f.value) })
      case JArray(xs) => xs flatMap { v => toXml(name, v) }
      case JField(n, v) => new XmlNode(name, toXml(n, v))
      case JInt(x) => new XmlElem(name, x.toString)
      case JDouble(x) => new XmlElem(name, x.toString)
      case JString(x) => new XmlElem(name, x)
      case JBool(x) => new XmlElem(name, x.toString)
      case JNull => new XmlElem(name, "null")
      case JNothing => Text("")
    }

    json match {
      case JField(n, v) => toXml(n, v)
      case JObject(fields) => fields flatMap { f => toXml(f.name, f.value) }
      case x => toXml("root", x)
    }
  }

  private[json] class XmlNode(name: String, children: Seq[Node]) extends Elem(null, name, xml.Null, TopScope, children :_*)

  private[json] class XmlElem(name: String, value: String) extends Elem(null, name, xml.Null, TopScope, Text(value))
}

}
}
