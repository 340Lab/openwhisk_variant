/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.openwhisk.core.controller.test

import java.time.Instant

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.sprayJsonUnmarshaller
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.server.Route
import org.apache.openwhisk.core.controller.WhiskActionsApi
import org.apache.openwhisk.core.controller.actions.ControllerActivationConfig
import org.apache.openwhisk.core.database.UserContext
import org.apache.openwhisk.core.entity._
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import spray.json.DefaultJsonProtocol._
import spray.json._

import scala.concurrent.duration.DurationInt

/**
 * Tests Actions API. These tests enable the secondary activation completion path.
 *
 * Unit tests of the controller service as a standalone component.
 * These tests exercise a fresh instance of the service object in memory -- these
 * tests do NOT communication with a whisk deployment.
 *
 * @Idioglossia
 * "using Specification DSL to write unit tests, as in should, must, not, be"
 * "using Specs2RouteTest DSL to chain HTTP requests for unit testing, as in ~>"
 */
@RunWith(classOf[JUnitRunner])
class ActionsApiWithDbPollingTests extends ControllerTestCommon with WhiskActionsApi {

  /** Actions API tests */
  behavior of "Actions API with DB Polling"

  val creds = WhiskAuthHelpers.newIdentity()
  val context = UserContext(creds)
  val namespace = EntityPath(creds.subject.asString)
  val collectionPath = s"/${EntityPath.DEFAULT}/${collection.path}"

  def aname() = MakeName.next("action_tests")

  val actionLimit = Exec.sizeLimit
  val parametersLimit = Parameters.sizeLimit

  override val controllerActivationConfig = ControllerActivationConfig(true, 60.seconds)

  it should "invoke a blocking action and retrieve result via db polling" in {
    implicit val tid = transid()
    val action = WhiskAction(namespace, aname(), jsDefault("??"))
    val activation = WhiskActivation(
      action.namespace,
      action.name,
      creds.subject,
      activationIdFactory.make(),
      start = Instant.now,
      end = Instant.now,
      response = ActivationResponse.success(Some(JsObject("test" -> "yes".toJson))),
      logs = ActivationLogs(Vector("first line", "second line")))
    put(entityStore, action)
    // storing the activation in the db will allow the db polling to retrieve it
    // the test harness makes sure the activation id observed by the test matches
    // the one generated by the api handler
    storeActivation(activation, false, false, context)
    try {
      Post(s"$collectionPath/${action.name}?blocking=true") ~> Route.seal(routes(creds)) ~> check {
        status should be(OK)
        val response = responseAs[JsObject]
        response should be(activation.withoutLogs.toExtendedJson())
      }

      // repeat invoke, get only result back
      Post(s"$collectionPath/${action.name}?blocking=true&result=true") ~> Route.seal(routes(creds)) ~> check {
        status should be(OK)
        val response = responseAs[JsObject]
        response should be(activation.resultAsJson)
        headers should contain(RawHeader(ActivationIdHeader, activation.activationId.asString))
      }
    } finally {
      deleteActivation(ActivationId(activation.docid.asString), context)
    }
  }

  it should "invoke a blocking action and return error response when activation fails" in {
    implicit val tid = transid()
    val action = WhiskAction(namespace, aname(), jsDefault("??"))
    val activation = WhiskActivation(
      action.namespace,
      action.name,
      creds.subject,
      activationIdFactory.make(),
      start = Instant.now,
      end = Instant.now,
      response = ActivationResponse.whiskError("test"))
    put(entityStore, action)
    // storing the activation in the db will allow the db polling to retrieve it
    // the test harness makes sure the activation id observed by the test matches
    // the one generated by the api handler
    storeActivation(activation, false, false, context)
    try {
      Post(s"$collectionPath/${action.name}?blocking=true") ~> Route.seal(routes(creds)) ~> check {
        status should be(InternalServerError)
        val response = responseAs[JsObject]
        response should be(activation.withoutLogs.toExtendedJson())
        headers should contain(RawHeader(ActivationIdHeader, response.fields("activationId").convertTo[String]))
      }
    } finally {
      deleteActivation(ActivationId(activation.docid.asString), context)
    }
  }
}
