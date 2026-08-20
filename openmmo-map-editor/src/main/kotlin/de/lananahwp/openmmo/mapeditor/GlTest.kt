package de.lananahwp.openmmo.mapeditor

import com.jogamp.opengl.GL
import com.jogamp.opengl.GL2
import com.jogamp.opengl.GLAutoDrawable
import com.jogamp.opengl.GLCapabilities
import com.jogamp.opengl.GLEventListener
import com.jogamp.opengl.GLProfile
import com.jogamp.opengl.awt.GLCanvas
import java.awt.Frame
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

/** Standalone JOGL display test: opens a window for ~1.5s and reports success/failure/hang. */
fun main() {
  var result = "PENDING"
  val done = CountDownLatch(1)
  SwingUtilities.invokeLater {
    try {
      val profile = GLProfile.get(GLProfile.GL2)
      val canvas = GLCanvas(GLCapabilities(profile))
      canvas.addGLEventListener(
          object : GLEventListener {
            override fun init(drawable: GLAutoDrawable) {
              (drawable.gl as GL2).glClearColor(0.2f, 0.4f, 0.8f, 1f)
            }

            override fun dispose(drawable: GLAutoDrawable) {}

            override fun display(drawable: GLAutoDrawable) {
              (drawable.gl as GL2).glClear(GL.GL_COLOR_BUFFER_BIT)
            }

            override fun reshape(drawable: GLAutoDrawable, x: Int, y: Int, width: Int, height: Int) {}
          })
      val frame = Frame("JOGL test")
      frame.add(canvas)
      frame.setSize(320, 240)
      frame.isVisible = true
      result = "WINDOW_SHOWN"
      Thread.sleep(1500)
      frame.dispose()
      result = "GLTEST_OK"
    } catch (t: Throwable) {
      result = "FAIL: ${t.message}"
    }
    done.countDown()
  }
  val finished = done.await(15, TimeUnit.SECONDS)
  System.out.println("GLTEST_RESULT: ${if (finished) result else "HANG (no completion in 15s)"}")
}
