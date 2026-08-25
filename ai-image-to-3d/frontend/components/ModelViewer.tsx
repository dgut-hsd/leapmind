"use client";

import { Bounds, Environment, OrbitControls, useGLTF } from "@react-three/drei";
import { Canvas } from "@react-three/fiber";
import { Suspense } from "react";

function Asset({ url }: { url: string }) {
  const gltf = useGLTF(url);
  return <primitive object={gltf.scene} />;
}

export default function ModelViewer({ url }: { url: string }) {
  return (
    <div className="h-[460px] w-full overflow-hidden border-2 border-ink bg-[#ddd8cb]">
      <Canvas camera={{ position: [2.5, 1.8, 3.5], fov: 40 }} shadows>
        <color attach="background" args={["#ddd8cb"]} />
        <ambientLight intensity={1.2} />
        <directionalLight position={[4, 8, 4]} intensity={2.5} castShadow />
        <Suspense fallback={null}>
          <Bounds fit clip observe margin={1.2}>
            <Asset url={url} />
          </Bounds>
          <Environment preset="studio" />
        </Suspense>
        <gridHelper args={[10, 20, "#7e7a70", "#b8b2a5"]} />
        <OrbitControls makeDefault />
      </Canvas>
    </div>
  );
}

