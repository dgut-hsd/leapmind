import * as THREE from "three";
import { Model } from "./model.js";
import { loadVRMAnimation } from "@/lib/VRMAnimation/loadVRMAnimation.js";
import { buildUrl } from "@/utils/buildUrl.js";
import { OrbitControls } from "three/examples/jsm/controls/OrbitControls";

/**
 * three.jsを使った3Dビューワー（JS 版本）
 * 从 viewer.ts 无损转换，移除了类型标注
 */
export class Viewer {
  isReady;
  model;

  _renderer;
  _clock;
  _scene;
  _camera;
  _cameraControls;
  _animationFrameId;
  _resizeHandler;
  _loadRequestId;
  _teachingScreen;
  _teachingScreenTexture;
  _teachingMode = false;

  constructor() {
    this.isReady = false;
    this._animationFrameId = null;
    this._resizeHandler = null;
    this._loadRequestId = 0;

    // scene
    const scene = new THREE.Scene();
    this._scene = scene;

    // light
    // 主光（方向光）：提供明确的方向性阴影与高光，略偏上右，增强立体感
    const directionalLight = new THREE.DirectionalLight(0xffffff, 1.2);
    directionalLight.position.set(0.6, 1.2, 0.8).normalize();
    scene.add(directionalLight);

    // 环境光：整体抬亮场景，填充阴影区域，避免过暗（过强会"洗白"颜色）
    const ambientLight = new THREE.AmbientLight(0xffffff, 0.8);
    scene.add(ambientLight);

    // 半球光：模拟天空与地面反射的漫射光，平衡上下方向的亮度与色温
    // 使用更温暖的光照颜色以保持自然肤色
    const hemiLight = new THREE.HemisphereLight(0xfff5e1, 0x555555, 0.7);
    hemiLight.position.set(0, 1, 0);
    scene.add(hemiLight);

    // // 正面补光（方向光）：柔化面部阴影，避免五官区域偏暗
    // const fillLight = new THREE.DirectionalLight(0xffffff, 0.6);
    // fillLight.position.set(0, 0.8, 1.0).normalize();
    // scene.add(fillLight);

    // animate
    this._clock = new THREE.Clock();
    this._clock.start();
  }

  async loadVrm(url) {
    const requestId = ++this._loadRequestId;
    if (this.model?.vrm) {
      this.unloadVRM();
    }

    // gltf and vrm
    const model = new Model(this._camera || new THREE.Object3D());
    this.model = model;

    try {
      await model.loadVRM(url);
      if (requestId !== this._loadRequestId || this.model !== model || !model.vrm) {
        model.unLoadVrm();
        return null;
      }

      model.vrm.scene.traverse((obj) => {
        obj.frustumCulled = false;
      });
      this._scene.add(model.vrm.scene);

      if (!model.idleAnimationManager) {
        const vrma = await loadVRMAnimation(buildUrl("/idle_loop.vrma"));
        if (vrma && requestId === this._loadRequestId) model.loadAnimation(vrma);
      }

      requestAnimationFrame(() => {
        if (requestId !== this._loadRequestId) return;
        if (this._teachingMode) {
          this.fitToTeachingScene();
        } else {
          this.fitToModel(0.9, 0.16);
        }
      });
      return model;
    } catch (error) {
      if (this.model === model) this.model = undefined;
      model.unLoadVrm();
      throw error;
    }
  }

  unloadVRM() {
    if (this.model?.vrm) {
      this._scene.remove(this.model.vrm.scene);
    }
    this.model?.unLoadVrm();
  }

  /**
   * Reactで管理しているCanvasを後から設定する
   */
  setup(canvas) {
    if (this._renderer) {
      this.dispose();
    }
    const parentElement = canvas.parentElement;
    const width = parentElement?.clientWidth || canvas.width;
    const height = parentElement?.clientHeight || canvas.height;
    // renderer
    this._renderer = new THREE.WebGLRenderer({
      canvas: canvas,
      alpha: true,
      antialias: true,
    });
    this._renderer.outputColorSpace = THREE.SRGBColorSpace;
    // 对于VRM模型（特别是vRoid Studio创建的），使用更温和的色调映射或禁用
    // ACESFilmicToneMapping可能导致肤色变灰，改用LinearToneMapping或ReinhardToneMapping
    this._renderer.toneMapping = THREE.LinearToneMapping; // 或 THREE.ReinhardToneMapping
    this._renderer.toneMappingExposure = 1.15; // 稍微提高曝光值以增加亮度
    this._renderer.setSize(width, height);
    this._renderer.setPixelRatio(window.devicePixelRatio);

    // camera
    this._camera = new THREE.PerspectiveCamera(20.0, width / height, 0.1, 20.0);
    this._camera.position.set(0, 1.3, 1.5);
    this._cameraControls?.target.set(0, 1.3, 0);
    this._cameraControls?.update();
    // camera controls
    this._cameraControls = new OrbitControls(
      this._camera,
      this._renderer.domElement
    );
    this._cameraControls.screenSpacePanning = true;
    this._cameraControls.update();

    this._resizeHandler = () => this.resize();
    window.addEventListener("resize", this._resizeHandler);
    this.isReady = true;
    this._clock.start();
    this.update();
  }

  /**
   * canvasの親要素を参照してサイズを変更する
   */
  resize() {
    if (!this._renderer) return;

    const parentElement = this._renderer.domElement.parentElement;
    if (!parentElement) return;

    this._renderer.setPixelRatio(window.devicePixelRatio);
    this._renderer.setSize(
      parentElement.clientWidth,
      parentElement.clientHeight
    );

    if (!this._camera) return;
    this._camera.aspect =
      parentElement.clientWidth / parentElement.clientHeight;
    this._camera.updateProjectionMatrix();
  }

  /**
   * VRMのheadノードを参照してカメラ位置を調整する
   */
  resetCamera() {
    const headNode = this.model?.vrm?.humanoid.getNormalizedBoneNode("head");

    if (headNode) {
      const headWPos = headNode.getWorldPosition(new THREE.Vector3());
      this._camera?.position.set(
        this._camera.position.x,
        headWPos.y,
        this._camera.position.z
      );
      this._cameraControls?.target.set(headWPos.x, headWPos.y, headWPos.z);
      this._cameraControls?.update();
    }
  }

  /**
   * 根据模型包围盒调整相机距离与目标点，使角色在容器中居中且完整显示
   * padding: 留白系数，>1 会稍微拉远
   */
  fitToModel(padding = 1.2, verticalOffsetRatio = 0) {
    if (!this.model?.vrm || !this._camera || !this._cameraControls) return;

    const sceneObject = this.model.vrm.scene;
    const box = new THREE.Box3().setFromObject(sceneObject);
    if (box.isEmpty()) return;

    const size = new THREE.Vector3();
    const center = new THREE.Vector3();
    box.getSize(size);
    box.getCenter(center);

    const fovRad = THREE.MathUtils.degToRad(this._camera.fov);
    const halfHeight = (size.y * padding) / 2;
    const halfWidth = (size.x * padding) / 2;
    const distForHeight = halfHeight / Math.tan(fovRad / 2);
    const distForWidth = halfWidth / (Math.tan(fovRad / 2) * this._camera.aspect);
    const distance = Math.max(distForHeight, distForWidth);

    // 计算垂直偏移后的观测目标点（正值使角色在屏幕中更靠下）
    const target = new THREE.Vector3(center.x, center.y + size.y * verticalOffsetRatio, center.z);

    // 放置到Z轴正方向，略微上移视角
    const eye = new THREE.Vector3(target.x, target.y + size.y * 0.05, target.z + distance);
    this._camera.position.copy(eye);
    this._cameraControls.target.copy(target);
    this._cameraControls.update();
  }

  /**
   * 将 PPT 作为 CanvasTexture 放入 Three.js 场景。
   * 课件与 VRM 共用相机、灯光和渲染循环，不是页面上的 DOM 浮层。
   */
  setTeachingSlide(slide) {
    if (!slide) {
      this.clearTeachingScreen();
      return;
    }

    this._teachingMode = true;
    if (!this._teachingScreen) {
      const group = new THREE.Group();
      group.name = "TeachingScreen3D";

      const back = new THREE.Mesh(
        new THREE.BoxGeometry(1.56, 0.96, 0.055),
        new THREE.MeshStandardMaterial({
          color: 0x172554,
          roughness: 0.42,
          metalness: 0.28,
        })
      );
      group.add(back);

      const screen = new THREE.Mesh(
        new THREE.PlaneGeometry(1.42, 0.8),
        new THREE.MeshBasicMaterial({ color: 0xffffff })
      );
      screen.name = "TeachingSlideSurface";
      screen.position.z = 0.031;
      group.add(screen);

      const tray = new THREE.Mesh(
        new THREE.BoxGeometry(1.62, 0.045, 0.12),
        new THREE.MeshStandardMaterial({
          color: 0x334155,
          roughness: 0.34,
          metalness: 0.48,
        })
      );
      tray.position.set(0, -0.515, 0.025);
      group.add(tray);

      group.position.set(0.72, 1.18, -0.12);
      group.rotation.y = -0.055;
      this._scene.add(group);
      this._teachingScreen = group;
    }

    const canvas = document.createElement("canvas");
    canvas.width = 1280;
    canvas.height = 720;
    const context = canvas.getContext("2d");
    if (!context) return;

    const gradient = context.createLinearGradient(0, 0, 1280, 720);
    gradient.addColorStop(0, "#f8fafc");
    gradient.addColorStop(0.62, "#eef2ff");
    gradient.addColorStop(1, "#dbeafe");
    context.fillStyle = gradient;
    context.fillRect(0, 0, 1280, 720);

    context.fillStyle = "#4338ca";
    context.fillRect(0, 0, 1280, 18);
    context.fillStyle = "#06b6d4";
    context.fillRect(0, 18, 350, 7);

    context.fillStyle = "#4f46e5";
    context.font = '700 30px "Microsoft YaHei", sans-serif';
    context.fillText(slide.tag || "LEAPMIND 课堂", 70, 88);

    context.fillStyle = "#0f172a";
    context.font = '900 64px "Microsoft YaHei", sans-serif';
    this._drawWrappedText(context, slide.title || "课堂课件", 70, 178, 1120, 78, 2);

    context.fillStyle = "#64748b";
    context.font = '500 31px "Microsoft YaHei", sans-serif';
    this._drawWrappedText(context, slide.subtitle || "", 72, 292, 1080, 44, 2);

    const points = Array.isArray(slide.points) ? slide.points.slice(0, 3) : [];
    points.forEach((point, index) => {
      const y = 388 + index * 88;
      context.fillStyle = index === 0 ? "#4f46e5" : "#0891b2";
      context.beginPath();
      context.arc(94, y - 8, 27, 0, Math.PI * 2);
      context.fill();
      context.fillStyle = "#ffffff";
      context.font = '800 26px "Microsoft YaHei", sans-serif';
      context.textAlign = "center";
      context.fillText(String(index + 1), 94, y + 1);
      context.textAlign = "left";
      context.fillStyle = "#1e293b";
      context.font = '650 32px "Microsoft YaHei", sans-serif';
      this._drawWrappedText(context, String(point), 145, y, 980, 40, 1);
    });

    context.fillStyle = "#64748b";
    context.font = '600 24px "Microsoft YaHei", sans-serif';
    context.textAlign = "right";
    context.fillText(
      `${slide.page || 1} / ${slide.totalPages || 1}`,
      1200,
      670
    );
    context.textAlign = "left";

    const texture = new THREE.CanvasTexture(canvas);
    texture.colorSpace = THREE.SRGBColorSpace;
    texture.anisotropy = Math.min(8, this._renderer?.capabilities?.getMaxAnisotropy?.() || 1);
    texture.needsUpdate = true;

    const screen = this._teachingScreen.getObjectByName("TeachingSlideSurface");
    if (screen?.material) {
      this._teachingScreenTexture?.dispose();
      screen.material.map = texture;
      screen.material.needsUpdate = true;
      this._teachingScreenTexture = texture;
    }

    requestAnimationFrame(() => this.fitToTeachingScene());
  }

  _drawWrappedText(context, text, x, y, maxWidth, lineHeight, maxLines) {
    const characters = Array.from(text || "");
    let line = "";
    let lineIndex = 0;

    for (let index = 0; index < characters.length; index += 1) {
      const testLine = line + characters[index];
      if (context.measureText(testLine).width > maxWidth && line) {
        context.fillText(line, x, y + lineIndex * lineHeight);
        line = characters[index];
        lineIndex += 1;
        if (lineIndex >= maxLines) return;
      } else {
        line = testLine;
      }
    }
    if (line && lineIndex < maxLines) {
      context.fillText(line, x, y + lineIndex * lineHeight);
    }
  }

  fitToTeachingScene() {
    if (!this.model?.vrm || !this._teachingScreen || !this._camera || !this._cameraControls) return;

    this.model.vrm.scene.position.x = -0.47;
    this.model.vrm.scene.updateMatrixWorld(true);
    this._teachingScreen.updateMatrixWorld(true);

    const box = new THREE.Box3()
      .setFromObject(this.model.vrm.scene)
      .union(new THREE.Box3().setFromObject(this._teachingScreen));
    if (box.isEmpty()) return;

    const size = box.getSize(new THREE.Vector3());
    const center = box.getCenter(new THREE.Vector3());
    const fovRad = THREE.MathUtils.degToRad(this._camera.fov);
    const distanceForHeight = (size.y * 0.58) / Math.tan(fovRad / 2);
    const distanceForWidth = (size.x * 0.58) / (Math.tan(fovRad / 2) * this._camera.aspect);
    const distance = Math.max(distanceForHeight, distanceForWidth);
    const target = new THREE.Vector3(center.x, center.y + size.y * 0.06, center.z);

    this._camera.position.set(target.x, target.y + size.y * 0.04, center.z + distance);
    this._cameraControls.target.copy(target);
    this._cameraControls.update();
  }

  clearTeachingScreen() {
    this._teachingMode = false;
    if (this.model?.vrm) this.model.vrm.scene.position.x = 0;
    if (!this._teachingScreen) return;

    this._scene.remove(this._teachingScreen);
    this._teachingScreen.traverse((object) => {
      object.geometry?.dispose?.();
      if (Array.isArray(object.material)) {
        object.material.forEach((material) => material.dispose());
      } else {
        object.material?.dispose?.();
      }
    });
    this._teachingScreenTexture?.dispose();
    this._teachingScreenTexture = undefined;
    this._teachingScreen = undefined;
  }

  update = () => {
    if (!this.isReady) return;
    this._animationFrameId = requestAnimationFrame(this.update);
    const delta = this._clock.getDelta();
    // update vrm components
    if (this.model) {
      this.model.update(delta);
    }

    if (this._renderer && this._camera) {
      this._renderer.render(this._scene, this._camera);
    }
  };

  dispose() {
    this.isReady = false;
    this._loadRequestId += 1;

    if (this._animationFrameId !== null) {
      cancelAnimationFrame(this._animationFrameId);
      this._animationFrameId = null;
    }
    if (this._resizeHandler) {
      window.removeEventListener("resize", this._resizeHandler);
      this._resizeHandler = null;
    }

    this.clearTeachingScreen();
    this.unloadVRM();
    this.model = undefined;
    this._cameraControls?.dispose();
    this._cameraControls = undefined;

    if (this._renderer) {
      this._renderer.dispose();
      this._renderer.forceContextLoss();
      this._renderer = undefined;
    }
    this._camera = undefined;
  }
}
