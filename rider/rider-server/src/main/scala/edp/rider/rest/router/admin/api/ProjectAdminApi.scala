/*-
 * <<
 * wormhole
 * ==
 * Copyright (C) 2016 - 2017 EDP
 * ==
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * >>
 */


package edp.rider.rest.router.admin.api

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Route
import edp.rider.common.RiderLogger
import edp.rider.monitor.{CacheMap, Dashboard}
import edp.rider.rest.persistence.dal.{ProjectDal, RelProjectNsDal, RelProjectUserDal}
import edp.rider.rest.persistence.entities._
import edp.rider.rest.router.JsonProtocol._
import edp.rider.rest.router.{ResponseJson, ResponseSeqJson, SessionClass}
import edp.rider.rest.util.AuthorizationProvider
import edp.rider.rest.util.CommonUtils._
import edp.rider.rest.util.ResponseUtils._
import slick.jdbc.MySQLProfile.api._

import scala.util.{Failure, Success}

class ProjectAdminApi(projectDal: ProjectDal, relProjectNsDal: RelProjectNsDal, relProjectUserDal: RelProjectUserDal) extends BaseAdminApiImpl(projectDal) with RiderLogger {

  override def getByIdRoute(route: String): Route = path(route / LongNumber) {
    id =>
      get {
        authenticateOAuth2Async[SessionClass]("rider", AuthorizationProvider.authorize) {
          session =>
            if (session.roleType != "admin") {
              riderLogger.warn(s"${session.userId} has no permission to access it.")
              complete(Forbidden, getHeader(403, session))
            }
            else {
              onComplete(projectDal.getById(id).mapTo[Option[ProjectUserNs]]) {
                case Success(projectOpt) => projectOpt match {
                  case Some(project) =>
                    riderLogger.info(s"user ${session.userId} select project where id is $id success.")
                    complete(OK, ResponseJson[ProjectUserNs](getHeader(200, session), project))
                  case None =>
                    riderLogger.warn(s"user ${session.userId} select project where id is $id success, but it doesn't exist.")
                    complete(OK, ResponseJson[String](getHeader(200, session), ""))
                }
                case Failure(ex) =>
                  riderLogger.error(s"user ${session.userId} select project where id is $id failed", ex)
                  complete(UnavailableForLegalReasons, getHeader(451, ex.getMessage, session))
              }
            }
        }
      }

  }

  def getByFilterRoute(route: String): Route = path(route) {
    get {
      parameter('visible.as[Boolean].?, 'name.as[String].?) {
        (visible, name) =>
          authenticateOAuth2Async[SessionClass]("rider", AuthorizationProvider.authorize) {
            session =>
              if (session.roleType != "admin") {
                riderLogger.warn(s"${session.userId} has no permission to access it.")
                complete(Forbidden, getHeader(403, session))
              }
              else {
                (visible, name) match {
                  case (None, Some(projectName)) =>
                    onComplete(projectDal.findByFilter(_.name === projectName).mapTo[Seq[Project]]) {
                      case Success(projects) =>
                        if (projects.isEmpty) {
                          riderLogger.info(s"user ${session.userId} check project name $projectName doesn't exist.")
                          complete(OK, getHeader(200, session))
                        }
                        else {
                          riderLogger.warn(s"user ${session.userId} check project name $projectName already exists.")
                          complete(Conflict, getHeader(409, s"$projectName project already exists", session))
                        }
                      case Failure(ex) =>
                        riderLogger.error(s"user ${session.userId} check project name $projectName does exist failed", ex)
                        complete(UnavailableForLegalReasons, getHeader(451, ex.getMessage, session))
                    }
                  case (_, None) =>
                    onComplete(projectDal.findAll.mapTo[Seq[Project]]) {
                      case Success(projects) =>
                        riderLogger.info(s"user ${session.userId} select all $route success.")
                        complete(OK, ResponseSeqJson[Project](getHeader(200, session), projects.sortBy(project => (project.active, project.createTime)).reverse))
                      case Failure(ex) =>
                        riderLogger.error(s"user ${session.userId} select all $route failed", ex)
                        complete(UnavailableForLegalReasons, getHeader(451, ex.getMessage, session))
                    }
                  case (_, _) =>
                    riderLogger.error(s"user ${session.userId} request url is not supported.")
                    complete(NotImplemented, getHeader(501, session))
                }
              }
          }
      }
    }

  }

  def postRoute(route: String): Route = path(route) {
    post {
      entity(as[SimpleProjectRel]) {
        simple =>
          authenticateOAuth2Async[SessionClass]("rider", AuthorizationProvider.authorize) {
            session =>
              if (session.roleType != "admin") {
                riderLogger.warn(s"${session.userId} has no permission to access it.")
                complete(Forbidden, getHeader(403, session))
              }
              else {
                val projectEntity = Project(0, simple.name, Some(simple.desc.getOrElse("")), simple.pic, simple.resCores, simple.resMemoryG, active = true, currentSec, session.userId, currentSec, session.userId)
                onComplete(projectDal.insert(projectEntity).mapTo[Project]) {
                  case Success(project) =>
                    riderLogger.info(s"user ${session.userId} inserted project $project success.")
                    val relNsEntity = simple.nsId.split(",").map(nsId => RelProjectNs(0, project.id, nsId.toLong, active = true, currentSec, session.userId, currentSec, session.userId)).toSeq
                    val relUserEntity = simple.userId.split(",").map(userId => RelProjectUser(0, project.id, userId.toLong, active = true, currentSec, session.userId, currentSec, session.userId)).toSeq
                    onComplete(relProjectNsDal.insert(relNsEntity).mapTo[Seq[RelProjectNs]]) {
                      case Success(relProjectNss) =>
                        riderLogger.info(s"user ${session.userId} inserted relProjectNs $relProjectNss success.")
                        onComplete(relProjectUserDal.insert(relUserEntity).mapTo[Seq[RelProjectUser]]) {
                          case Success(relProjectUsers) =>
                            riderLogger.info(s"user ${session.userId} inserted relProjectUser $relProjectUsers success.")
                            Dashboard.createDashboard(project.id, simple.name)
                            complete(OK, ResponseJson[Project](getHeader(200, session), project))
                          case Failure(ex) =>
                            riderLogger.error(s"user ${session.userId} inserted relProjectUser $relUserEntity failed", ex)
                            complete(UnavailableForLegalReasons, getHeader(451, ex.getMessage, session))
                        }
                      case Failure(ex) =>
                        riderLogger.error(s"user ${session.userId} inserted relProjectNs $relNsEntity failed", ex)
                        complete(UnavailableForLegalReasons, getHeader(451, ex.getMessage, session))
                    }
                  case Failure(ex) =>
                    riderLogger.error(s"user ${session.userId} inserted project $projectEntity failed", ex)
                    if (ex.toString.contains("Duplicate entry"))
                      complete(Conflict, getHeader(409, s"${simple.name} project already exists", session))
                    else
                      complete(UnavailableForLegalReasons, getHeader(451, ex.getMessage, session))
                }
              }
          }
      }
    }

  }


  def putRoute(route: String): Route = path(route) {
    put {
      entity(as[ProjectUserNs]) {
        entity =>
          authenticateOAuth2Async[SessionClass]("rider", AuthorizationProvider.authorize) {
            session =>
              if (session.roleType != "admin") {
                riderLogger.warn(s"${session.userId} has no permission to access it.")
                complete(Forbidden, getHeader(403, session))
              }
              else {
                val projectEntity = Project(entity.id, entity.name, Some(entity.desc.getOrElse("")), entity.pic, entity.resCores, entity.resMemoryG, entity.active, entity.createTime, entity.createBy, currentSec, session.userId)
                onComplete(projectDal.update(projectEntity).mapTo[Int]) {
                  case Success(project) =>
                    riderLogger.info(s"user ${session.userId} updated project $projectEntity success.")
                    val relNsEntity = entity.nsId.split(",").map(nsId => RelProjectNs(0, entity.id, nsId.toLong, active = true, currentSec, session.userId, currentSec, session.userId)).toSeq
                    val relUserEntity = entity.userId.split(",").map(userId => RelProjectUser(0, entity.id, userId.toLong, active = true, currentSec, session.userId, currentSec, session.userId)).toSeq
                    onComplete(relProjectNsDal.deleteByFilter(_.projectId === entity.id).mapTo[Int]) {
                      case Success(nsIds) =>
                        riderLogger.info(s"user ${session.userId} deleted relProjectNs where project id is ${entity.id} success.")
                        onComplete(relProjectNsDal.insert(relNsEntity).mapTo[Seq[RelProjectNs]]) {
                          case Success(relProjectNss) =>
                            riderLogger.info(s"user ${session.userId} inserted relProjectNs $relNsEntity success.")
                            onComplete(relProjectUserDal.deleteByFilter(_.projectId === entity.id).mapTo[Int]) {
                              case Success(userIds) =>
                                riderLogger.info(s"user ${session.userId} deleted relProjectUser where project id is ${entity.id} success.")
                                onComplete(relProjectUserDal.insert(relUserEntity).mapTo[Seq[RelProjectUser]]) {
                                  case Success(relProjectUsers) =>
                                    riderLogger.info(s"user ${session.userId} inserted relProjectUser $relUserEntity success.")
                                    complete(OK, ResponseJson[Project](getHeader(200, session), projectEntity))
                                  case Failure(ex) =>
                                    riderLogger.error(s"user ${session.userId} inserted relProjectUser $relUserEntity failed", ex)
                                    complete(UnavailableForLegalReasons, getHeader(451, ex.getMessage, session))
                                }
                              case Failure(ex) =>
                                riderLogger.error(s"user ${session.userId} deleted relProjectUser where project id is ${entity.id} failed", ex)
                                complete(UnavailableForLegalReasons, getHeader(451, ex.getMessage, session))
                            }
                          case Failure(ex) =>
                            riderLogger.error(s"user ${session.userId} inserted relProjectNs $relNsEntity failed", ex)
                            complete(UnavailableForLegalReasons, getHeader(451, ex.getMessage, session))
                        }
                      case Failure(ex) =>
                        riderLogger.error(s"user ${session.userId} deleted relProjectNs where project id is ${entity.id} failed", ex)
                        complete(UnavailableForLegalReasons, getHeader(451, ex.getMessage, session))
                    }
                  case Failure(ex) =>
                    riderLogger.error(s"user ${session.userId} updated project $projectEntity failed", ex)
                    complete(UnavailableForLegalReasons, getHeader(451, ex.getMessage, session))
                }
              }
          }
      }
    }

  }
}
