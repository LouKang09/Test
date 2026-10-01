const tabs = [...document.querySelectorAll(".tab")];
const panels = [...document.querySelectorAll(".panel")];

tabs.forEach((tab) => {
  tab.addEventListener("click", () => {
    tabs.forEach((item) => item.classList.toggle("active", item === tab));
    panels.forEach((panel) => panel.classList.toggle("active", panel.id === tab.dataset.tab));
  });
});

// Scratch card
const canvas = document.querySelector("#scratchCanvas");
const resetScratch = document.querySelector("#resetScratch");
const revealBadge = document.querySelector("#revealBadge");
const ctx = canvas.getContext("2d");
let scratching = false;

function paintScratchLayer() {
  const rect = canvas.getBoundingClientRect();
  const dpr = Math.max(window.devicePixelRatio || 1, 1);
  canvas.width = Math.floor(rect.width * dpr);
  canvas.height = Math.floor(rect.height * dpr);
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.globalCompositeOperation = "source-over";
  ctx.fillStyle = "#28231f";
  ctx.fillRect(0, 0, rect.width, rect.height);

  ctx.fillStyle = "rgba(255,255,255,.12)";
  for (let x = -20; x < rect.width + 30; x += 22) {
    ctx.save();
    ctx.translate(x, 0);
    ctx.rotate(-0.25);
    ctx.fillRect(0, -30, 8, rect.height + 60);
    ctx.restore();
  }

  ctx.fillStyle = "#fffaf1";
  ctx.font = "700 16px 'IBM Plex Mono', monospace";
  ctx.textAlign = "center";
  ctx.textBaseline = "middle";
  ctx.fillText("SCRATCH HERE", rect.width / 2, rect.height / 2);
  revealBadge.hidden = true;
}

function scratchAt(event) {
  if (!scratching) return;
  const box = canvas.getBoundingClientRect();
  const x = event.clientX - box.left;
  const y = event.clientY - box.top;

  ctx.globalCompositeOperation = "destination-out";
  ctx.beginPath();
  ctx.arc(x, y, 24, 0, Math.PI * 2);
  ctx.fill();

  const pixels = ctx.getImageData(0, 0, canvas.width, canvas.height).data;
  let transparent = 0;
  const sampleStep = 4 * 64;
  for (let i = 3; i < pixels.length; i += sampleStep) {
    if (pixels[i] === 0) transparent++;
  }
  const sampleCount = Math.ceil(pixels.length / sampleStep);
  if (transparent / sampleCount > 0.45) revealBadge.hidden = false;
}

canvas.addEventListener("pointerdown", (event) => {
  scratching = true;
  canvas.setPointerCapture(event.pointerId);
  scratchAt(event);
});
canvas.addEventListener("pointermove", scratchAt);
["pointerup", "pointercancel"].forEach((name) => {
  canvas.addEventListener(name, () => (scratching = false));
});
resetScratch.addEventListener("click", paintScratchLayer);
window.addEventListener("resize", paintScratchLayer);
paintScratchLayer();

// Spinner
const prizes = ["10% OFF", "TRY AGAIN", "FREE DRINK", "₱100", "BONUS", "₱50"];
const wheel = document.querySelector("#wheel");
const spinButton = document.querySelector("#spinButton");
const spinResult = document.querySelector("#spinResult");
let rotation = 0;
let spinning = false;

spinButton.addEventListener("click", () => {
  if (spinning) return;
  spinning = true;
  spinButton.disabled = true;
  spinButton.textContent = "Spinning…";
  spinResult.textContent = "";

  const selected = Math.floor(Math.random() * prizes.length);
  const slice = 360 / prizes.length;
  rotation += 5 * 360 + (360 - (selected * slice + slice / 2));
  wheel.style.transform = `rotate(${rotation}deg)`;

  setTimeout(() => {
    spinResult.textContent = `Result: ${prizes[selected]}`;
    spinButton.disabled = false;
    spinButton.textContent = "Spin the wheel";
    spinning = false;
  }, 3200);
});

// Quiz
const quiz = [
  {
    question: "Which browser API is ideal for drawing a scratch-card coating?",
    choices: ["Canvas API", "Web Storage", "Fetch API", "History API"],
    answer: 0,
  },
  {
    question: "Which event family works well for mouse, pen, and touch input?",
    choices: ["Pointer events", "Keyboard events", "Hash events", "Print events"],
    answer: 0,
  },
  {
    question: "What can rotate a prize wheel smoothly without a library?",
    choices: ["CSS transform + transition", "Cookies", "localStorage", "WebSocket"],
    answer: 0,
  },
];

const questionText = document.querySelector("#questionText");
const questionCount = document.querySelector("#questionCount");
const answers = document.querySelector("#answers");
const scoreText = document.querySelector("#scoreText");
const nextQuestion = document.querySelector("#nextQuestion");

let questionIndex = 0;
let score = 0;
let selected = null;

function renderQuestion() {
  const item = quiz[questionIndex];
  questionText.textContent = item.question;
  questionCount.textContent = `${questionIndex + 1} / ${quiz.length}`;
  scoreText.textContent = `Score: ${score}`;
  answers.innerHTML = "";
  selected = null;
  nextQuestion.disabled = true;
  nextQuestion.textContent = questionIndex === quiz.length - 1 ? "See score" : "Next question";

  item.choices.forEach((choice, index) => {
    const button = document.createElement("button");
    button.className = "answer";
    button.textContent = `${String.fromCharCode(65 + index)}. ${choice}`;
    button.addEventListener("click", () => chooseAnswer(index));
    answers.appendChild(button);
  });
}

function chooseAnswer(index) {
  if (selected !== null) return;
  selected = index;
  const item = quiz[questionIndex];
  if (index === item.answer) score++;

  [...answers.children].forEach((button, buttonIndex) => {
    button.disabled = true;
    if (buttonIndex === item.answer) button.classList.add("correct");
    if (buttonIndex === index && index !== item.answer) button.classList.add("wrong");
  });

  scoreText.textContent = `Score: ${score}`;
  nextQuestion.disabled = false;
}

nextQuestion.addEventListener("click", () => {
  if (questionIndex === -1) {
    questionIndex = 0;
    score = 0;
    renderQuestion();
    return;
  }

  if (questionIndex === quiz.length - 1) {
    questionText.textContent = `You scored ${score} / ${quiz.length}`;
    questionCount.textContent = "COMPLETE";
    answers.innerHTML = "";
    nextQuestion.textContent = "Play again";
    nextQuestion.disabled = false;
    questionIndex = -1;
    return;
  }

  questionIndex++;
  renderQuestion();
});

renderQuestion();
