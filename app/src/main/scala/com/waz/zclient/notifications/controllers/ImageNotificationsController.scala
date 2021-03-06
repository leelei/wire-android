/**
 * Wire
 * Copyright (C) 2016 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.zclient.notifications.controllers

import android.app.NotificationManager
import android.graphics.Bitmap
import android.net.Uri
import android.support.v4.app.NotificationCompat
import com.waz.ZLog._
import com.waz.bitmap.BitmapUtils
import com.waz.model.{AssetData, AssetId}
import com.waz.service.ZMessaging
import com.waz.service.assets.AssetService.BitmapResult
import com.waz.service.images.BitmapSignal
import com.waz.threading.Threading
import com.waz.ui.MemoryImageCache.BitmapRequest.Single
import com.waz.utils.LoggedTry
import com.waz.utils.events.Signal
import com.waz.zclient.{Injectable, Injector, R, WireContext}
import com.waz.zclient.utils.ContextUtils._
import com.waz.zclient.utils.IntentUtils._

class ImageNotificationsController(cxt: WireContext)(implicit inj: Injector) extends Injectable {

  import ImageNotificationsController._
  implicit val eventContext = cxt.eventContext
  implicit val context = cxt

  val zms = inject[Signal[Option[ZMessaging]]].collect { case Some(z) => z }
  val notManager = inject[NotificationManager]

  val savedImageId = Signal[Option[AssetId]](None)
  val savedImageUri = Signal[Uri]()

  def showImageSavedNotification(imageId: String, uri: Uri) = Option(imageId).map(AssetId).zip(Option(uri)).foreach {
    case (id, ur) =>
      savedImageId ! Some(id)
      savedImageUri ! uri
  }

  def dismissImageSavedNotification() = {
    notManager.cancel(ZETA_SAVE_IMAGE_NOTIFICATION_ID)
    savedImageId ! None
  }

  //TODO use image controller when available from messages rewrite branch
  zms.zip(savedImageId).flatMap {
    case (zms, Some(imageId)) =>
      zms.assetsStorage.signal(imageId).flatMap {
        case data@AssetData.IsImage() => BitmapSignal(data, Single(getDimenPx(R.dimen.notification__image_saving__image_width)), zms.imageLoader, zms.imageCache)
        case _ => Signal.empty[BitmapResult]
      }
    case _ => Signal.empty[BitmapResult]
  }.zip(savedImageUri).on(Threading.Ui) {
    case (BitmapResult.BitmapLoaded(bitmap, _), uri) => showBitmap(bitmap, uri)
    case (_, uri) => showBitmap(null, uri)
  }

  private def showBitmap(bitmap: Bitmap, uri: Uri): Unit = {
    val summaryText = getString(R.string.notification__image_saving__content__subtitle)
    val notificationTitle = getString(R.string.notification__image_saving__content__title)

    val notificationStyle = new NotificationCompat.BigPictureStyle()
      .bigPicture(bitmap)
      .setSummaryText(summaryText)

    val builder = new NotificationCompat.Builder(cxt)
      .setContentTitle(notificationTitle)
      .setContentText(summaryText)
      .setSmallIcon(R.drawable.ic_menu_save_image_gallery)
      .setLargeIcon(BitmapUtils.cropRect(bitmap, toPx(largeIconSizeDp)))
      .setStyle(notificationStyle)
      .setContentIntent(getGalleryIntent(cxt, uri))
      .addAction(R.drawable.ic_menu_share, getString(R.string.notification__image_saving__action__share), getPendingShareIntent(cxt, uri)).setLocalOnly(true).setAutoCancel(true)

    def showNotification() = notManager.notify(ZETA_SAVE_IMAGE_NOTIFICATION_ID, builder.build())

    LoggedTry(showNotification()).recover { case e =>
      error(s"Notify failed: try without bitmap. Error: $e")
      builder.setLargeIcon(null)
      try showNotification()
      catch {
        case e: Throwable => error("second display attempt failed, aborting")
      }
    }
  }
}

object ImageNotificationsController {
  val largeIconSizeDp = 64
  val ZETA_SAVE_IMAGE_NOTIFICATION_ID: Int = 1339274
  private implicit val tag: LogTag = logTagFor[ImageNotificationsController]
}
