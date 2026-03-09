#!/usr/bin/env python3
"""
Train migration difficulty classifier and export to ONNX.

Usage:
    python tools/train_classifier.py --data training-data.json --output models/classifier.onnx
    python tools/train_classifier.py --data training-data.npz --output models/classifier.onnx

Supports input as JSON (from TrainingDataGenerator) or .npz with 'features' and 'labels' arrays.
Requires: pip install scikit-learn skl2onnx numpy
"""
import argparse
import json
import numpy as np
from sklearn.ensemble import GradientBoostingClassifier
from sklearn.model_selection import cross_val_score
from skl2onnx import convert_sklearn
from skl2onnx.common.data_types import FloatTensorType


def load_data(path: str):
    if path.endswith(".npz"):
        data = np.load(path, allow_pickle=False)
        return data["features"], data["labels"]
    if path.endswith(".json"):
        with open(path) as f:
            obj = json.load(f)
        features = np.array(obj["features"], dtype=np.float32)
        labels = np.array(obj["labels"], dtype=np.int64)
        return features, labels
    raise ValueError("Data path must be .npz or .json")


def train(data_path: str, output_path: str, n_estimators: int = 200, cv: int = 5) -> None:
    X, y = load_data(data_path)
    if X.ndim != 2 or y.ndim != 1 or len(X) != len(y):
        raise ValueError("features must be 2D, labels 1D, same length")

    clf = GradientBoostingClassifier(
        n_estimators=n_estimators,
        max_depth=6,
        learning_rate=0.1,
        subsample=0.8,
        min_samples_leaf=5,
    )

    scores = None
    if cv > 1 and len(np.unique(y)) > 1:
        scores = cross_val_score(clf, X, y, cv=min(cv, len(X) // 2 or 1), scoring="accuracy")
        print(f"CV accuracy: {scores.mean():.3f} ± {scores.std():.3f}")

    clf.fit(X, y)

    initial_type = [("input", FloatTensorType([None, X.shape[1]]))]
    onnx_model = convert_sklearn(clf, initial_types=initial_type, target_opset=17)

    with open(output_path, "wb") as f:
        f.write(onnx_model.SerializeToString())

    labels_out = ["TRIVIAL", "LOW", "MEDIUM", "HIGH", "VERY_HIGH"]
    meta_path = output_path.replace(".onnx", "_labels.json")
    meta = {"labels": labels_out}
    if scores is not None:
        meta["cv_accuracy"] = float(scores.mean())
    with open(meta_path, "w") as f:
        json.dump(meta, f, indent=2)

    print(f"Model saved to {output_path}")


def main():
    parser = argparse.ArgumentParser(description="Train migration classifier and export ONNX")
    parser.add_argument("--data", required=True, help="Path to training data (.json or .npz)")
    parser.add_argument("--output", default="models/classifier.onnx", help="Output ONNX path")
    parser.add_argument("--n-estimators", type=int, default=200, help="GradientBoosting n_estimators")
    parser.add_argument("--cv", type=int, default=5, help="Cross-validation folds (0 to disable)")
    args = parser.parse_args()
    train(args.data, args.output, n_estimators=args.n_estimators, cv=args.cv or 0)


if __name__ == "__main__":
    main()
