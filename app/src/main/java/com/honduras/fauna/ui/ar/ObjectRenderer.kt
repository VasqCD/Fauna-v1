package com.honduras.fauna.ui.ar

import android.content.Context
import android.opengl.GLES20
import com.google.ar.core.Anchor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

class ObjectRenderer {

    private var objectProgram = 0
    private var objectPositionParam = 0
    private var objectModelViewProjectionParam = 0
    private var objectColorParam = 0

    // Vértices para un cubo simple
    private val cubeVertices = floatArrayOf(
        // Front face
        -0.1f, -0.1f,  0.1f,
         0.1f, -0.1f,  0.1f,
         0.1f,  0.1f,  0.1f,
        -0.1f,  0.1f,  0.1f,

        // Back face
        -0.1f, -0.1f, -0.1f,
        -0.1f,  0.1f, -0.1f,
         0.1f,  0.1f, -0.1f,
         0.1f, -0.1f, -0.1f,

        // Top face
        -0.1f,  0.1f, -0.1f,
        -0.1f,  0.1f,  0.1f,
         0.1f,  0.1f,  0.1f,
         0.1f,  0.1f, -0.1f,

        // Bottom face
        -0.1f, -0.1f, -0.1f,
         0.1f, -0.1f, -0.1f,
         0.1f, -0.1f,  0.1f,
        -0.1f, -0.1f,  0.1f,

        // Right face
         0.1f, -0.1f, -0.1f,
         0.1f,  0.1f, -0.1f,
         0.1f,  0.1f,  0.1f,
         0.1f, -0.1f,  0.1f,

        // Left face
        -0.1f, -0.1f, -0.1f,
        -0.1f, -0.1f,  0.1f,
        -0.1f,  0.1f,  0.1f,
        -0.1f,  0.1f, -0.1f
    )

    private val cubeIndices = shortArrayOf(
        0,  1,  2,    0,  2,  3,    // front
        4,  5,  6,    4,  6,  7,    // back
        8,  9,  10,   8,  10, 11,   // top
        12, 13, 14,   12, 14, 15,   // bottom
        16, 17, 18,   16, 18, 19,   // right
        20, 21, 22,   20, 22, 23    // left
    )

    // Colores para diferentes tipos de objetos
    private val colors = arrayOf(
        floatArrayOf(1.0f, 0.0f, 0.0f, 1.0f), // Rojo
        floatArrayOf(0.0f, 1.0f, 0.0f, 1.0f), // Verde
        floatArrayOf(0.0f, 0.0f, 1.0f, 1.0f), // Azul
        floatArrayOf(1.0f, 1.0f, 0.0f, 1.0f), // Amarillo
        floatArrayOf(1.0f, 0.0f, 1.0f, 1.0f), // Magenta
        floatArrayOf(0.0f, 1.0f, 1.0f, 1.0f)  // Cian
    )

    private lateinit var vertexBuffer: FloatBuffer
    private lateinit var indexBuffer: ShortBuffer

    fun createOnGlThread(context: Context) {
        // Crear buffers
        vertexBuffer = ByteBuffer.allocateDirect(cubeVertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(cubeVertices)
        vertexBuffer.position(0)

        indexBuffer = ByteBuffer.allocateDirect(cubeIndices.size * 2)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()
            .put(cubeIndices)
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

        objectProgram = createProgram(vertexShader, fragmentShader)
        objectPositionParam = GLES20.glGetAttribLocation(objectProgram, "a_Position")
        objectModelViewProjectionParam = GLES20.glGetUniformLocation(objectProgram, "u_ModelViewProjection")
        objectColorParam = GLES20.glGetUniformLocation(objectProgram, "u_Color")
    }

    fun drawObjects(projectionMatrix: FloatArray, viewMatrix: FloatArray, anchors: List<Anchor>) {
        if (anchors.isEmpty()) return

        // Habilitar depth testing para objetos 3D
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)

        GLES20.glUseProgram(objectProgram)
        GLES20.glEnableVertexAttribArray(objectPositionParam)
        GLES20.glVertexAttribPointer(objectPositionParam, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        val modelViewProjectionMatrix = FloatArray(16)
        val modelMatrix = FloatArray(16)
        val viewProjectionMatrix = FloatArray(16)

        android.opengl.Matrix.multiplyMM(viewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

        anchors.forEachIndexed { index, anchor ->
            if (anchor.trackingState != com.google.ar.core.TrackingState.TRACKING) return@forEachIndexed

            // Obtener la pose del anchor
            anchor.pose.toMatrix(modelMatrix, 0)

            // Agregar una pequeña elevación para que el cubo esté sobre la superficie
            android.opengl.Matrix.translateM(modelMatrix, 0, 0.0f, 0.1f, 0.0f)

            // Rotar el cubo lentamente para que sea más visible
            val time = System.currentTimeMillis() / 1000.0f
            android.opengl.Matrix.rotateM(modelMatrix, 0, time * 50.0f * (index + 1), 0.0f, 1.0f, 0.0f)

            android.opengl.Matrix.multiplyMM(modelViewProjectionMatrix, 0, viewProjectionMatrix, 0, modelMatrix, 0)
            GLES20.glUniformMatrix4fv(objectModelViewProjectionParam, 1, false, modelViewProjectionMatrix, 0)

            // Usar un color diferente para cada objeto
            val color = colors[index % colors.size]
            GLES20.glUniform4fv(objectColorParam, 1, color, 0)

            GLES20.glDrawElements(GLES20.GL_TRIANGLES, cubeIndices.size, GLES20.GL_UNSIGNED_SHORT, indexBuffer)
        }

        GLES20.glDisableVertexAttribArray(objectPositionParam)
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
