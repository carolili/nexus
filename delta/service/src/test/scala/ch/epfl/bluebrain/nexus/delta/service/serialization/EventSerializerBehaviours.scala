package ch.epfl.bluebrain.nexus.delta.service.serialization

import ch.epfl.bluebrain.nexus.delta.sdk.model.Event
import ch.epfl.bluebrain.nexus.testkit.{CirceEq, EitherValuable}
import io.circe.Json
import io.circe.parser._
import org.scalatest.Inspectors
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import scala.reflect.ClassTag

trait EventSerializerBehaviours extends Matchers with Inspectors with EitherValuable with CirceEq {
  this: AnyFlatSpecLike =>

  def eventToJsonSerializer[E <: Event: ClassTag](manifest: String, mapping: Map[E, Json]): Unit = {
    val E          = implicitly[ClassTag[E]]
    val serializer = new EventSerializer

    it should s"correctly serialize ${E.runtimeClass.getSimpleName}" in {
      forAll(mapping) { case (event, json) =>
        val binary = serializer.toBinary(event)
        parse(new String(binary)).rightValue should equalIgnoreArrayOrder(json)
      }
    }

    it should s"yield the correct manifest for ${E.runtimeClass.getSimpleName}" in {
      forAll(mapping.keySet) { event =>
        serializer.manifest(event) shouldEqual manifest
      }
    }
  }

  def jsonToEventDeserializer[E <: Event: ClassTag](manifest: String, mapping: Map[E, Json]): Unit = {
    val E          = implicitly[ClassTag[E]]
    val serializer = new EventSerializer

    it should s"correctly deserialize ${E.runtimeClass.getSimpleName}" in {
      forAll(mapping) { case (event, json) =>
        val binary = json.noSpaces.getBytes
        serializer.fromBinary(binary, manifest) shouldEqual event
      }
    }

    it should s"fail deserialization of ${E.runtimeClass.getSimpleName} with incorrect manifest" in {
      forAll(mapping) { case (_, json) =>
        val binary = json.noSpaces.getBytes
        intercept[IllegalArgumentException] {
          serializer.fromBinary(binary, "incorrect")
        }
      }
    }
  }

}