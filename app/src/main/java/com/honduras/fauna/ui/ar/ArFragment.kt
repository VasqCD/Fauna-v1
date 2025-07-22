package com.honduras.fauna.ui.ar

import android.Manifest
import android.content.pm.PackageManager
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.ar.core.*
import com.google.ar.core.exceptions.*
import com.honduras.fauna.databinding.FragmentArBinding
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ArCameraFragment : Fragment(), GLSurfaceView.Renderer {

    private var _binding: FragmentArBinding? = null
    private val binding get() = _binding!!

    private var session: Session? = null
    private var displayRotationHelper: DisplayRotationHelper? = null
    private val backgroundRenderer = BackgroundRenderer()
    private val planeRenderer = PlaneRenderer()
    private val objectRenderer = ObjectRenderer() // Agregar el renderer de objetos 3D
    private val anchors = mutableListOf<Anchor>()

    companion object {
        private const val CAMERA_PERMISSION_CODE = 0
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentArBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        displayRotationHelper = DisplayRotationHelper(requireContext())

        // Configurar GLSurfaceView
        binding.surfaceView.apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(this@ArCameraFragment)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            setWillNotDraw(false)
        }

        // Configurar botón de reset
        binding.btnResetAr.setOnClickListener {
            resetSession()
        }

        // Configurar touch listener para colocar objetos
        binding.surfaceView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                onSingleTap(event)
            }
            true
        }

        // Verificar permisos
        if (!hasCameraPermission()) {
            requestCameraPermission()
        } else {
            initializeSession()
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(
            requireActivity(),
            arrayOf(Manifest.permission.CAMERA),
            CAMERA_PERMISSION_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initializeSession()
            } else {
                showError("Permiso de cámara requerido para ARCore")
            }
        }
    }

    private fun initializeSession() {
        try {
            when (ArCoreApk.getInstance().requestInstall(requireActivity(), true)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    binding.tvArInfo.text = "Instalando ARCore..."
                    return
                }
                ArCoreApk.InstallStatus.INSTALLED -> {
                    createSession()
                }
            }
        } catch (e: UnavailableApkTooOldException) {
            showError("ARCore APK demasiado antigua")
        } catch (e: UnavailableArcoreNotInstalledException) {
            showError("ARCore no está instalado")
        } catch (e: UnavailableSdkTooOldException) {
            showError("SDK demasiado antigua para ARCore")
        } catch (e: Exception) {
            showError("Error inicializando ARCore: ${e.message}")
        }
    }

    private fun createSession() {
        try {
            session = Session(requireContext())

            val config = Config(session).apply {
                planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
            }

            session?.configure(config)
            binding.tvArInfo.text = "Mueve el dispositivo para detectar superficies"

        } catch (e: Exception) {
            showError("Error configurando sesión AR: ${e.message}")
        }
    }

    private fun resetSession() {
        // Ejecutar todo en el hilo del GLSurfaceView para evitar concurrencia
        binding.surfaceView.queueEvent {
            try {
                // Limpiar datos locales
                anchors.clear()
                queuedSingleTaps.clear()

                // Actualizar UI
                requireActivity().runOnUiThread {
                    binding.tvArInfo.text = "Reiniciando AR..."
                }

                // Cerrar sesión de forma segura
                session?.let { currentSession ->
                    try {
                        currentSession.pause()
                        currentSession.close()
                    } catch (e: Exception) {
                        // Ignorar errores al cerrar sesión
                    }
                }
                session = null

                // Pequeña pausa para que ARCore se limpie
                Thread.sleep(300)

                // Recrear sesión
                createSessionSafely()

            } catch (e: Exception) {
                requireActivity().runOnUiThread {
                    binding.tvArInfo.text = "Error reiniciando AR: ${e.message}"
                    Toast.makeText(requireContext(), "Error reiniciando AR. Intenta nuevamente.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun createSessionSafely() {
        try {
            // Verificar que el contexto sigue siendo válido
            if (!isAdded || isDetached) {
                return
            }

            // Crear nueva sesión ARCore
            session = Session(requireContext())

            // Configurar sesión
            val config = Config(session).apply {
                planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
            }

            session?.configure(config)

            // Reanudar la sesión para activar la cámara
            session?.resume()

            // Reinicializar el background renderer para la nueva sesión
            backgroundRenderer.createOnGlThread(requireContext())

            // Configurar la textura de la cámara con el nuevo renderer
            session?.setCameraTextureName(backgroundRenderer.textureId)

            // Actualizar UI en el hilo principal
            requireActivity().runOnUiThread {
                binding.tvArInfo.text = "Sesión reiniciada - Mueve el dispositivo"
            }

        } catch (e: Exception) {
            requireActivity().runOnUiThread {
                showError("Error creando sesión AR: ${e.message}")
            }
        }
    }

    private fun onSingleTap(event: MotionEvent) {
        // Guardar las coordenadas del toque para procesarlas en el próximo frame
        queuedSingleTaps.add(Pair(event.x, event.y))
    }

    private val queuedSingleTaps = mutableListOf<Pair<Float, Float>>()

    private fun handleTaps(frame: Frame) {
        if (queuedSingleTaps.isEmpty()) return

        val hits = frame.hitTest(queuedSingleTaps.first().first, queuedSingleTaps.first().second)
        queuedSingleTaps.removeAt(0)

        for (hit in hits) {
            val trackable = hit.trackable
            if (trackable is Plane && trackable.isPoseInPolygon(hit.hitPose)) {
                val anchor = hit.createAnchor()
                anchors.add(anchor)

                requireActivity().runOnUiThread {
                    binding.tvArInfo.text = "Objeto colocado! Toques: ${anchors.size}"
                }
                break
            }
        }
    }

    private fun showError(message: String) {
        binding.tvArInfo.text = message
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }

    // GLSurfaceView.Renderer methods
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)

        try {
            backgroundRenderer.createOnGlThread(requireContext())
            planeRenderer.createOnGlThread(requireContext(), "models/trigrid.png")
            objectRenderer.createOnGlThread(requireContext()) // Inicializar el renderer de objetos 3D
        } catch (e: Exception) {
            showError("Error inicializando renderizadores: ${e.message}")
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        displayRotationHelper?.onSurfaceChanged(width, height)
        GLES20.glViewport(0, 0, width, height)

        // Forzar actualización de la geometría de display cuando cambia el tamaño
        session?.let { session ->
            displayRotationHelper?.updateSessionIfNeeded(session)
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val session = this.session ?: return
        val displayRotationHelper = this.displayRotationHelper ?: return

        try {
            // Actualizar rotación de pantalla
            displayRotationHelper.updateSessionIfNeeded(session)

            session.setCameraTextureName(backgroundRenderer.textureId)

            val frame = session.update()
            val camera = frame.camera

            if (camera.trackingState == TrackingState.TRACKING) {
                requireActivity().runOnUiThread {
                    binding.tvArInfo.text = "Toca en una superficie para colocar objetos (${anchors.size})"
                }
            }

            // Renderizar fondo de cámara
            backgroundRenderer.draw(frame)

            // Obtener matrices de cámara como FloatArray
            val projectionMatrix = FloatArray(16)
            val viewMatrix = FloatArray(16)
            camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f)
            camera.getViewMatrix(viewMatrix, 0)

            // Renderizar planos detectados
            planeRenderer.drawPlanes(
                projectionMatrix,
                viewMatrix,
                session.getAllTrackables(Plane::class.java)
            )

            // Renderizar objetos 3D en los anchors
            objectRenderer.drawObjects(projectionMatrix, viewMatrix, anchors)

            // Manejar toques en la pantalla
            handleTaps(frame)

        } catch (e: Exception) {
            // Error silencioso para evitar spam en logs
        }
    }

    override fun onResume() {
        super.onResume()

        if (session == null) {
            initializeSession()
            return
        }

        try {
            session?.resume()
        } catch (e: CameraNotAvailableException) {
            showError("Cámara no disponible")
        }

        binding.surfaceView.onResume()
        displayRotationHelper?.onResume()
    }

    override fun onPause() {
        super.onPause()

        if (session != null) {
            displayRotationHelper?.onPause()
            binding.surfaceView.onPause()
            session?.pause()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        session?.close()
        session = null
        _binding = null
    }
}
