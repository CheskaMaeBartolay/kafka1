const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_parent_123";
const BOB_UID = "bob_child_456";
const CHARLIE_UID = "charlie_stranger_789";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user: cannot read users", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
});

test("Authenticated user: can create own user profile", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      email: "alice@example.com",
      displayName: "Alice Guardian",
      createdAt: new Date(),
    })
  );
});

test("Authenticated user: cannot read another user's profile", async () => {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.firestore().collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      email: "alice@example.com",
      displayName: "Alice Guardian",
      createdAt: new Date(),
    });
  });

  const charlieDb = testEnv.authenticatedContext(CHARLIE_UID).firestore();
  await assertFails(charlieDb.collection("users").doc(ALICE_UID).get());
});

test("Authenticated user: can register own device", async () => {
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertSucceeds(
    bobDb.collection("devices").doc("bob_pixel_8").set({
      deviceId: "bob_pixel_8",
      userId: BOB_UID,
      name: "Bob's Pixel",
      model: "Pixel 8",
      mode: "DEVICE",
      isOnline: true,
      batteryPercentage: 85,
      isCharging: false,
      locationSharingPaused: false,
      lastActive: new Date(),
      updatedAt: new Date(),
    })
  );
});

test("Authenticated user: can create pairing code", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertSucceeds(
    aliceDb.collection("pairing_codes").doc("123456").set({
      code: "123456",
      monitorDeviceId: "alice_galaxy_s24",
      monitorUserId: ALICE_UID,
      monitorDeviceName: "Alice's Galaxy",
      isUsed: false,
      expiresAt: new Date(Date.now() + 300000),
      createdAt: new Date(),
    })
  );
});

test("Authenticated user: cannot tamper with another monitor's pairing code", async () => {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.firestore().collection("pairing_codes").doc("123456").set({
      code: "123456",
      monitorDeviceId: "alice_galaxy_s24",
      monitorUserId: ALICE_UID,
      monitorDeviceName: "Alice's Galaxy",
      isUsed: false,
      expiresAt: new Date(Date.now() + 300000),
      createdAt: new Date(),
    });
  });

  const charlieDb = testEnv.authenticatedContext(CHARLIE_UID).firestore();
  await assertFails(
    charlieDb.collection("pairing_codes").doc("123456").delete()
  );
});
