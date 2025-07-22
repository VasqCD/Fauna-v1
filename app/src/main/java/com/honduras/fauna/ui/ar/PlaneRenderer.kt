package com.honduras.fauna.ui.ar

import android.content.Context
import android.opengl.GLES20
import android.opengl.Matrix
import com.google.ar.core.Plane
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

class PlaneRenderer {

    private var planeProgram = 0
    private var planePositionParam = 0
    private var planeModelViewProjectionParam = 0
    private var planeColorParam = 0

    private val planeColor = floatArrayOf(0.0f, 0.7f, 1.0f, 0.15f) // Azul suave con alta transparencia

    // Vértices para un plano con patrón de malla
    private val planeVertices = floatArrayOf(
        // Plano principal
        -1.0f, 0.0f, -1.0f,
        1.0f, 0.0f, -1.0f,
        1.0f, 0.0f, 1.0f,
        -1.0f, 0.0f, 1.0f,

        // Líneas de la malla (horizontal)
        -1.0f, 0.001f, -0.5f,
        1.0f, 0.001f, -0.5f,
        -1.0f, 0.001f, 0.0f,
        1.0f, 0.001f, 0.0f,
        -1.0f, 0.001f, 0.5f,
        1.0f, 0.001f, 0.5f,

        // Líneas de la malla (vertical)
        -0.5f, 0.001f, -1.0f,
        -0.5f, 0.001f, 1.0f,
        0.0f, 0.001f, -1.0f,
        0.0f, 0.001f, 1.0f,
        0.5f, 0.001f, -1.0f,
        0.5f, 0.001f, 1.0f
    )

    private val planeIndices = shortArrayOf(
        // Plano base (muy transparente)
        0, 1, 2, 0, 2, 3
    )

    private val gridLineIndices = shortArrayOf(
        // Líneas horizontales
        4, 5, 6, 7, 8, 9,
        // Líneas verticales
        10, 11, 12, 13, 14, 15
    )

    private lateinit var vertexBuffer: FloatBuffer
    private lateinit var indexBuffer: ShortBuffer

    fun createOnGlThread(context: Context, textureFilename: String) {
        // Crear buffers
        vertexBuffer = ByteBuffer.allocateDirect(planeVertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(planeVertices)
        vertexBuffer.position(0)

        indexBuffer = ByteBuffer.allocateDirect(planeIndices.size * 2)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()
            .put(planeIndices)
        indexBuffer.position(0)

        // Crear shader program
        val vertexShader = """
            attribute vec4 a_Position;
            uniform mat4 u_ModelViewProjection;
            void main() {
                gl_Position = u_ModelViewProjection * a_Position;
            }
        """.trimIndent()

        val fragmentShader = """
            precision mediump float;
            uniform vec4 u_Color;
            void main() {
                gl_FragColor = u_Color;
            }
        """.trimIndent()

        planeProgram = createProgram(vertexShader, fragmentShader)
        planePositionParam = GLES20.glGetAttribLocation(planeProgram, "a_Position")
        planeModelViewProjectionParam = GLES20.glGetUniformLocation(planeProgram, "u_ModelViewProjection")
        planeColorParam = GLES20.glGetUniformLocation(planeProgram, "u_Color")
    }

    fun drawPlanes(projectionMatrix: FloatArray, viewMatrix: FloatArray, planes: Collection<Plane>) {
        if (planes.isEmpty()) return

        // Habilitar transparencia/blending
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(false) // No escribir en depth buffer para transparencias

        GLES20.glUseProgram(planeProgram)
        GLES20.glEnableVertexAttribArray(planePositionParam)
        GLES20.glVertexAttribPointer(planePositionParam, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        val modelViewProjectionMatrix = FloatArray(16)
        val modelMatrix = FloatArray(16)
        val viewProjectionMatrix = FloatArray(16)

        android.opengl.Matrix.multiplyMM(viewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

        for (plane in planes) {
            if (plane.trackingState != com.google.ar.core.TrackingState.TRACKING) continue

            // Crear matriz de modelo para el plano
            plane.centerPose.toMatrix(modelMatrix, 0)

            // Escalar el plano según su extensión pero limitarlo para no ser demasiado grande
            val scaleX = minOf(plane.extentX, 2.0f)
            val scaleZ = minOf(plane.extentZ, 2.0f)
            android.opengl.Matrix.scaleM(modelMatrix, 0, scaleX, 1.0f, scaleZ)

            android.opengl.Matrix.multiplyMM(modelViewProjectionMatrix, 0, viewProjectionMatrix, 0, modelMatrix, 0)
            GLES20.glUniformMatrix4fv(planeModelViewProjectionParam, 1, false, modelViewProjectionMatrix, 0)

            // Renderizar el plano base con transparencia
            GLES20.glUniform4fv(planeColorParam, 1, planeColor, 0)
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, planeIndices.size, GLES20.GL_UNSIGNED_SHORT, indexBuffer)

            // Renderizar líneas de borde más visibles
            val borderColor = floatArrayOf(0.0f, 0.9f, 1.0f, 0.6f) // Azul más intenso para bordes
            GLES20.glUniform4fv(planeColorParam, 1, borderColor, 0)
            GLES20.glLineWidth(2.0f)

            // Dibujar contorno del plano
            val borderIndices = shortArrayOf(0, 1, 1, 2, 2, 3, 3, 0)
            val borderIndexBuffer = ByteBuffer.allocateDirect(borderIndices.size * 2)
                .order(ByteOrder.nativeOrder())
                .asShortBuffer()
                .put(borderIndices)
            borderIndexBuffer.position(0)

            GLES20.glDrawElements(GLES20.GL_LINES, borderIndices.size, GLES20.GL_UNSIGNED_SHORT, borderIndexBuffer)
        }

        GLES20.glDisableVertexAttribArray(planePositionParam)

        // Restaurar configuración de OpenGL
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glLineWidth(1.0f)
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)

        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        return program
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        return shader
    }
}
