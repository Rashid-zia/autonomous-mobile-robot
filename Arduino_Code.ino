X#include <MeAuriga.h>
#include <math.h>

#define CMD_SERIAL Serial

// Hardware components
MeEncoderOnBoard Encoder_1(SLOT1);
MeEncoderOnBoard Encoder_2(SLOT2);
MeUltrasonicSensor ultrasonicSensor(PORT_7);
MeLineFollower lineFollower(PORT_10);
MeRGBLed led;

// Modes
enum Mode { MODE_STOP = 0,
            MODE_LINE,
            MODE_OBSTACLE,
            MODE_HAND,
            MODE_DISTANCE };
Mode currentMode = MODE_STOP;

// LED configuration
const uint8_t numLEDs = 3;  
// Speed settings (0–255)
const uint8_t forwardSpeed = 200;
const uint8_t turnSpeed = 150;
const uint8_t reverseSpeed = 180;

// Distance & timing thresholds
const int STOP_DISTANCE_CM = 70;          // Distance threshold to obstacle
const int MAX_DISTANCE_CM = 300;          // Treat sensor zeros as “far away”
const unsigned long SCAN_DELAY_MS = 400;  // How long to turn for each 90° scan
const unsigned long LOOP_DELAY_MS = 100;  // Main loop delay

// Derived turn timings
const unsigned long TURN_90_MS = SCAN_DELAY_MS;
const unsigned long TURN_180_MS = 2 * TURN_90_MS;
const unsigned long TURN_CORRECT_MS = TURN_90_MS / 2;

// Timing for periodic updates
unsigned long lastCheck = 0;
const unsigned long interval = 60;

bool isMovingLinear = false;
unsigned long odomLastTime = 0;
double estDistanceCm = 0;

// Per-mode calibration constants (cm per ms)
const double CM_PER_MS_LINE = 0.040;
const double CM_PER_MS_OBSTACLE = 0.030;
const double CM_PER_MS_HAND = 0.025;

// Function prototypes
void processCommand(char cmd);
void setModeLED(Mode mode);
void updateMode();

void handleLine();
void handleObstacle();
void handleHand();

void moveForward();
void turnLeft();
void turnRight();
void moveBackward();
void stopMotors();

// Additional helpers for obstacle avoidance
int readDistance();
void pivotInPlaceLeft(uint8_t pwm = turnSpeed);
void pivotInPlaceRight(uint8_t pwm = turnSpeed);
void correctOrientation();


void updateEstimate() {
  unsigned long now = millis();
  if (isMovingLinear) {
    unsigned long dt = now - odomLastTime;
    double factor;
    switch (currentMode) {
      case MODE_LINE:
        factor = CM_PER_MS_LINE;
        break;
      case MODE_OBSTACLE:
        factor = CM_PER_MS_OBSTACLE;
        break;
      case MODE_HAND:
        factor = CM_PER_MS_HAND;
        break;
      default:
        factor = 0.0;
    }
    estDistanceCm += dt * factor;
  }
  odomLastTime = now;
}

void setup() {
  CMD_SERIAL.begin(9600);
  Serial.begin(115200);

  // Initialize LEDs
  led.setpin(44);
  for (uint8_t i = 0; i < numLEDs; i++) {
    led.setColorAt(i, 0, 0, 0);
  }
  led.show();

  stopMotors();
  odomLastTime = millis();
}

void loop() {
  // Read BLE commands
  if (CMD_SERIAL.available()) {
    processCommand(CMD_SERIAL.read());
  }

  // Periodic mode handling
  if (millis() - lastCheck >= interval) {
    lastCheck = millis();
    updateMode();
  }
}

// Handle incoming ASCII commands
void processCommand(char cmd) {
  switch (cmd) {
    case 'L':
      updateEstimate();
      estDistanceCm = 0;
      isMovingLinear = false;
      currentMode = MODE_LINE;
      Serial.println("Mode: Line Following");
      setModeLED(MODE_LINE);
      break;
    case 'O':
      updateEstimate();
      estDistanceCm = 0;
      isMovingLinear = false;
      currentMode = MODE_OBSTACLE;
      Serial.println("Mode: Obstacle Avoidance");
      setModeLED(MODE_OBSTACLE);
      break;
    case 'H':
      updateEstimate();
      estDistanceCm = 0;
      isMovingLinear = false;
      currentMode = MODE_HAND;
      Serial.println("Mode: Hand Following");
      setModeLED(MODE_HAND);
      break;
    case 'S':
      updateEstimate();
      isMovingLinear = false;
      currentMode = MODE_STOP;
      Serial.println("Mode: Stop");
      setModeLED(MODE_STOP);
      stopMotors();
      break;
    case 'D':
      updateEstimate();
      CMD_SERIAL.print("DIST:");
      CMD_SERIAL.println(estDistanceCm, 1);
      break;
    default:
      break;
  }
}

// Update onboard LED to indicate current mode
void setModeLED(Mode mode) {
  for (uint8_t i = 0; i < numLEDs; i++) {
    led.setColorAt(i, 0, 0, 0);
  }
  if (mode > MODE_STOP && mode <= MODE_HAND) {
    // use red for all modes by default
    led.setColorAt(mode - 1, 255, 0, 0);
  }
  led.show();
}

// Call the appropriate handler based on currentMode
void updateMode() {
  switch (currentMode) {
    case MODE_LINE:
      handleLine();
      break;
    case MODE_OBSTACLE:
      handleObstacle();
      break;
    case MODE_HAND:
      handleHand();
      break;
    case MODE_STOP:
    default:
      stopMotors();
      break;
  }
}

// ===== LINE FOLLOWING =====
void handleLine() {
  uint8_t s = lineFollower.readSensors();
  switch (s) {
    case 0b00:  // both black
      moveForward();
      break;
    case 0b01:  // right white, left black
      turnLeft();
      break;
    case 0b10:  // right black, left white
      turnRight();
      break;
    case 0b11:  // both white
      moveBackward();
      break;
    default:
      stopMotors();
      break;
  }
}

// ===== HAND FOLLOWING =====
void handleHand() {
  int distance = ultrasonicSensor.distanceCm();
  if (distance > 50) {
    stopMotors();
  } else if (distance >= 30 && distance <= 50) {
    moveForward();
  } else if (distance >= 15 && distance < 30) {
    stopMotors();
  } else if (distance < 15 && distance > 0) {
    moveBackward();
  } else {
    // distance == 0 (no echo) or negative
    stopMotors();
  }
  delay(100);
}

// ===== OBSTACLE AVOIDANCE =====
void handleObstacle() {
  int frontDist = readDistance();
  if (frontDist > STOP_DISTANCE_CM) {
    // clear, move forward
    for (uint8_t i = 0; i < numLEDs; i++) led.setColorAt(i, 0, 0, 0);
    led.show();
    moveForward();
    delay(LOOP_DELAY_MS);
    return;
  }

  stopMotors();
  for (uint8_t i = 0; i < numLEDs; i++) led.setColorAt(i, 255, 0, 0);
  led.show();
  delay(100);

  // scan left
  for (uint8_t i = 0; i < numLEDs; i++) led.setColorAt(i, 0, 255, 0);
  led.show();
  pivotInPlaceLeft();
  delay(TURN_90_MS);
  int leftDist = readDistance();

  // scan right
  for (uint8_t i = 0; i < numLEDs; i++) led.setColorAt(i, 0, 0, 255);
  led.show();
  pivotInPlaceRight();
  delay(TURN_180_MS);
  int rightDist = readDistance();

  // recenter
  for (uint8_t i = 0; i < numLEDs; i++) led.setColorAt(i, 0, 0, 0);
  led.show();
  pivotInPlaceLeft();
  delay(TURN_90_MS);
  stopMotors();

  // choose direction
  if (leftDist > rightDist) pivotInPlaceLeft();
  else pivotInPlaceRight();
  delay(TURN_180_MS);
  stopMotors();

  // correct orientation
  correctOrientation();

  // resume
  for (uint8_t i = 0; i < numLEDs; i++) led.setColorAt(i, 0, 0, 0);
  led.show();
  moveForward();
  delay(LOOP_DELAY_MS);
}

// ===== MOTOR CONTROL =====
void moveForward() {
  updateEstimate();
  isMovingLinear = true;
  Encoder_1.setMotorPwm(-forwardSpeed);
  Encoder_2.setMotorPwm(forwardSpeed);
}

void turnLeft() {
  updateEstimate();
  isMovingLinear = false;
  Encoder_1.setMotorPwm(-turnSpeed);
  Encoder_2.setMotorPwm(0);
}

void turnRight() {
  updateEstimate();
  isMovingLinear = false;
  Encoder_1.setMotorPwm(0);
  Encoder_2.setMotorPwm(turnSpeed);
}

void moveBackward() {
  updateEstimate();
  isMovingLinear = true;
  Encoder_1.setMotorPwm(reverseSpeed);
  Encoder_2.setMotorPwm(-reverseSpeed);
}

void stopMotors() {
  updateEstimate();
  isMovingLinear = false;
  Encoder_1.setMotorPwm(0);
  Encoder_2.setMotorPwm(0);
}

// ===== OBSTACLE HELPERS =====
int readDistance() {
  int d = ultrasonicSensor.distanceCm();
  return (d <= 0 ? MAX_DISTANCE_CM : d);
}

void pivotInPlaceLeft(uint8_t pwm) {
  updateEstimate();
  isMovingLinear = false;
  Encoder_1.setMotorPwm(-pwm);
  Encoder_2.setMotorPwm(-pwm);
}

void pivotInPlaceRight(uint8_t pwm) {
  updateEstimate();
  isMovingLinear = false;
  Encoder_1.setMotorPwm(pwm);
  Encoder_2.setMotorPwm(pwm);
}

void correctOrientation() {
  int leftCorr, rightCorr;
  // mini-scan left (~45°)
  pivotInPlaceLeft();
  delay(TURN_CORRECT_MS);
  leftCorr = readDistance();
  // mini-scan right (~90° past center)
  pivotInPlaceRight();
  delay(2 * TURN_CORRECT_MS);
  rightCorr = readDistance();
  // re-center (~45°)
  pivotInPlaceLeft();
  delay(TURN_CORRECT_MS);
  stopMotors();
  if (rightCorr > leftCorr) {
    pivotInPlaceRight();
  } else {
    pivotInPlaceLeft();
  }
  delay(TURN_CORRECT_MS);
  stopMotors();
}
